/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.backup.fullbackup;

import android.app.backup.IFullBackupRestoreObserver;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.AppWidgetBackupBridge;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.KeyValueAdbBackupEngine;
import com.android.server.backup.RefactoredBackupManagerService;
import com.android.server.backup.utils.AppBackupUtils;
import com.android.server.backup.utils.PasswordUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Full backup task variant used for adb backup.
 */
public class PerformAdbBackupTask extends FullBackupTask implements BackupRestoreTask {

    private RefactoredBackupManagerService backupManagerService;
    FullBackupEngine mBackupEngine;
    final AtomicBoolean mLatch;

    ParcelFileDescriptor mOutputFile;
    DeflaterOutputStream mDeflater;
    boolean mIncludeApks;
    boolean mIncludeObbs;
    boolean mIncludeShared;
    boolean mDoWidgets;
    boolean mAllApps;
    boolean mIncludeSystem;
    boolean mCompress;
    boolean mKeyValue;
    ArrayList<String> mPackages;
    PackageInfo mCurrentTarget;
    String mCurrentPassword;
    String mEncryptPassword;
    private final int mCurrentOpToken;

    public PerformAdbBackupTask(RefactoredBackupManagerService backupManagerService,
            ParcelFileDescriptor fd, IFullBackupRestoreObserver observer,
            boolean includeApks, boolean includeObbs, boolean includeShared, boolean doWidgets,
            String curPassword, String encryptPassword, boolean doAllApps, boolean doSystem,
            boolean doCompress, boolean doKeyValue, String[] packages, AtomicBoolean latch) {
        super(observer);
        this.backupManagerService = backupManagerService;
        mCurrentOpToken = backupManagerService.generateRandomIntegerToken();
        mLatch = latch;

        mOutputFile = fd;
        mIncludeApks = includeApks;
        mIncludeObbs = includeObbs;
        mIncludeShared = includeShared;
        mDoWidgets = doWidgets;
        mAllApps = doAllApps;
        mIncludeSystem = doSystem;
        mPackages = (packages == null)
                ? new ArrayList<String>()
                : new ArrayList<>(Arrays.asList(packages));
        mCurrentPassword = curPassword;
        // when backing up, if there is a current backup password, we require that
        // the user use a nonempty encryption password as well.  if one is supplied
        // in the UI we use that, but if the UI was left empty we fall back to the
        // current backup password (which was supplied by the user as well).
        if (encryptPassword == null || "".equals(encryptPassword)) {
            mEncryptPassword = curPassword;
        } else {
            mEncryptPassword = encryptPassword;
        }
        if (RefactoredBackupManagerService.MORE_DEBUG) {
            Slog.w(RefactoredBackupManagerService.TAG,
                    "Encrypting backup with passphrase=" + mEncryptPassword);
        }
        mCompress = doCompress;
        mKeyValue = doKeyValue;
    }

    void addPackagesToSet(TreeMap<String, PackageInfo> set, List<String> pkgNames) {
        for (String pkgName : pkgNames) {
            if (!set.containsKey(pkgName)) {
                try {
                    PackageInfo info = backupManagerService.getPackageManager().getPackageInfo(
                            pkgName,
                            PackageManager.GET_SIGNATURES);
                    set.put(pkgName, info);
                } catch (NameNotFoundException e) {
                    Slog.w(RefactoredBackupManagerService.TAG,
                            "Unknown package " + pkgName + ", skipping");
                }
            }
        }
    }

    private OutputStream emitAesBackupHeader(StringBuilder headerbuf,
            OutputStream ofstream) throws Exception {
        // User key will be used to encrypt the master key.
        byte[] newUserSalt = backupManagerService
                .randomBytes(PasswordUtils.PBKDF2_SALT_SIZE);
        SecretKey userKey = PasswordUtils
                .buildPasswordKey(RefactoredBackupManagerService.PBKDF_CURRENT, mEncryptPassword,
                        newUserSalt,
                        PasswordUtils.PBKDF2_HASH_ROUNDS);

        // the master key is random for each backup
        byte[] masterPw = new byte[256 / 8];
        backupManagerService.getRng().nextBytes(masterPw);
        byte[] checksumSalt = backupManagerService
                .randomBytes(PasswordUtils.PBKDF2_SALT_SIZE);

        // primary encryption of the datastream with the random key
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec masterKeySpec = new SecretKeySpec(masterPw, "AES");
        c.init(Cipher.ENCRYPT_MODE, masterKeySpec);
        OutputStream finalOutput = new CipherOutputStream(ofstream, c);

        // line 4: name of encryption algorithm
        headerbuf.append(PasswordUtils.ENCRYPTION_ALGORITHM_NAME);
        headerbuf.append('\n');
        // line 5: user password salt [hex]
        headerbuf.append(PasswordUtils.byteArrayToHex(newUserSalt));
        headerbuf.append('\n');
        // line 6: master key checksum salt [hex]
        headerbuf.append(PasswordUtils.byteArrayToHex(checksumSalt));
        headerbuf.append('\n');
        // line 7: number of PBKDF2 rounds used [decimal]
        headerbuf.append(PasswordUtils.PBKDF2_HASH_ROUNDS);
        headerbuf.append('\n');

        // line 8: IV of the user key [hex]
        Cipher mkC = Cipher.getInstance("AES/CBC/PKCS5Padding");
        mkC.init(Cipher.ENCRYPT_MODE, userKey);

        byte[] IV = mkC.getIV();
        headerbuf.append(PasswordUtils.byteArrayToHex(IV));
        headerbuf.append('\n');

        // line 9: master IV + key blob, encrypted by the user key [hex].  Blob format:
        //    [byte] IV length = Niv
        //    [array of Niv bytes] IV itself
        //    [byte] master key length = Nmk
        //    [array of Nmk bytes] master key itself
        //    [byte] MK checksum hash length = Nck
        //    [array of Nck bytes] master key checksum hash
        //
        // The checksum is the (master key + checksum salt), run through the
        // stated number of PBKDF2 rounds
        IV = c.getIV();
        byte[] mk = masterKeySpec.getEncoded();
        byte[] checksum = PasswordUtils
                .makeKeyChecksum(RefactoredBackupManagerService.PBKDF_CURRENT,
                        masterKeySpec.getEncoded(),
                        checksumSalt, PasswordUtils.PBKDF2_HASH_ROUNDS);

        ByteArrayOutputStream blob = new ByteArrayOutputStream(IV.length + mk.length
                + checksum.length + 3);
        DataOutputStream mkOut = new DataOutputStream(blob);
        mkOut.writeByte(IV.length);
        mkOut.write(IV);
        mkOut.writeByte(mk.length);
        mkOut.write(mk);
        mkOut.writeByte(checksum.length);
        mkOut.write(checksum);
        mkOut.flush();
        byte[] encryptedMk = mkC.doFinal(blob.toByteArray());
        headerbuf.append(PasswordUtils.byteArrayToHex(encryptedMk));
        headerbuf.append('\n');

        return finalOutput;
    }

    private void finalizeBackup(OutputStream out) {
        try {
            // A standard 'tar' EOF sequence: two 512-byte blocks of all zeroes.
            byte[] eof = new byte[512 * 2]; // newly allocated == zero filled
            out.write(eof);
        } catch (IOException e) {
            Slog.w(RefactoredBackupManagerService.TAG,
                    "Error attempting to finalize backup stream");
        }
    }

    @Override
    public void run() {
        String includeKeyValue = mKeyValue ? ", including key-value backups" : "";
        Slog.i(RefactoredBackupManagerService.TAG,
                "--- Performing adb backup" + includeKeyValue + " ---");

        TreeMap<String, PackageInfo> packagesToBackup = new TreeMap<>();
        FullBackupObbConnection obbConnection = new FullBackupObbConnection(
                backupManagerService);
        obbConnection.establish();  // we'll want this later

        sendStartBackup();

        // doAllApps supersedes the package set if any
        if (mAllApps) {
            List<PackageInfo> allPackages =
                    backupManagerService.getPackageManager().getInstalledPackages(
                            PackageManager.GET_SIGNATURES);
            for (int i = 0; i < allPackages.size(); i++) {
                PackageInfo pkg = allPackages.get(i);
                // Exclude system apps if we've been asked to do so
                if (mIncludeSystem == true
                        || ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0)) {
                    packagesToBackup.put(pkg.packageName, pkg);
                }
            }
        }

        // If we're doing widget state as well, ensure that we have all the involved
        // host & provider packages in the set
        if (mDoWidgets) {
            // TODO: http://b/22388012
            List<String> pkgs =
                    AppWidgetBackupBridge.getWidgetParticipants(UserHandle.USER_SYSTEM);
            if (pkgs != null) {
                if (RefactoredBackupManagerService.MORE_DEBUG) {
                    Slog.i(RefactoredBackupManagerService.TAG,
                            "Adding widget participants to backup set:");
                    StringBuilder sb = new StringBuilder(128);
                    sb.append("   ");
                    for (String s : pkgs) {
                        sb.append(' ');
                        sb.append(s);
                    }
                    Slog.i(RefactoredBackupManagerService.TAG, sb.toString());
                }
                addPackagesToSet(packagesToBackup, pkgs);
            }
        }

        // Now process the command line argument packages, if any. Note that explicitly-
        // named system-partition packages will be included even if includeSystem was
        // set to false.
        if (mPackages != null) {
            addPackagesToSet(packagesToBackup, mPackages);
        }

        // Now we cull any inapplicable / inappropriate packages from the set.  This
        // includes the special shared-storage agent package; we handle that one
        // explicitly at the end of the backup pass. Packages supporting key-value backup are
        // added to their own queue, and handled after packages supporting fullbackup.
        ArrayList<PackageInfo> keyValueBackupQueue = new ArrayList<>();
        Iterator<Entry<String, PackageInfo>> iter = packagesToBackup.entrySet().iterator();
        while (iter.hasNext()) {
            PackageInfo pkg = iter.next().getValue();
            if (!AppBackupUtils.appIsEligibleForBackup(pkg.applicationInfo)
                    || AppBackupUtils.appIsStopped(pkg.applicationInfo)) {
                iter.remove();
                if (RefactoredBackupManagerService.DEBUG) {
                    Slog.i(RefactoredBackupManagerService.TAG, "Package " + pkg.packageName
                            + " is not eligible for backup, removing.");
                }
            } else if (AppBackupUtils.appIsKeyValueOnly(pkg)) {
                iter.remove();
                if (RefactoredBackupManagerService.DEBUG) {
                    Slog.i(RefactoredBackupManagerService.TAG, "Package " + pkg.packageName
                            + " is key-value.");
                }
                keyValueBackupQueue.add(pkg);
            }
        }

        // flatten the set of packages now so we can explicitly control the ordering
        ArrayList<PackageInfo> backupQueue =
                new ArrayList<>(packagesToBackup.values());
        FileOutputStream ofstream = new FileOutputStream(mOutputFile.getFileDescriptor());
        OutputStream out = null;

        PackageInfo pkg = null;
        try {
            boolean encrypting = (mEncryptPassword != null && mEncryptPassword.length() > 0);

            // Only allow encrypted backups of encrypted devices
            if (backupManagerService.deviceIsEncrypted() && !encrypting) {
                Slog.e(RefactoredBackupManagerService.TAG,
                        "Unencrypted backup of encrypted device; aborting");
                return;
            }

            OutputStream finalOutput = ofstream;

            // Verify that the given password matches the currently-active
            // backup password, if any
            if (!backupManagerService.backupPasswordMatches(mCurrentPassword)) {
                if (RefactoredBackupManagerService.DEBUG) {
                    Slog.w(RefactoredBackupManagerService.TAG,
                            "Backup password mismatch; aborting");
                }
                return;
            }

            // Write the global file header.  All strings are UTF-8 encoded; lines end
            // with a '\n' byte.  Actual backup data begins immediately following the
            // final '\n'.
            //
            // line 1: "ANDROID BACKUP"
            // line 2: backup file format version, currently "5"
            // line 3: compressed?  "0" if not compressed, "1" if compressed.
            // line 4: name of encryption algorithm [currently only "none" or "AES-256"]
            //
            // When line 4 is not "none", then additional header data follows:
            //
            // line 5: user password salt [hex]
            // line 6: master key checksum salt [hex]
            // line 7: number of PBKDF2 rounds to use (same for user & master) [decimal]
            // line 8: IV of the user key [hex]
            // line 9: master key blob [hex]
            //     IV of the master key, master key itself, master key checksum hash
            //
            // The master key checksum is the master key plus its checksum salt, run through
            // 10k rounds of PBKDF2.  This is used to verify that the user has supplied the
            // correct password for decrypting the archive:  the master key decrypted from
            // the archive using the user-supplied password is also run through PBKDF2 in
            // this way, and if the result does not match the checksum as stored in the
            // archive, then we know that the user-supplied password does not match the
            // archive's.
            StringBuilder headerbuf = new StringBuilder(1024);

            headerbuf.append(RefactoredBackupManagerService.BACKUP_FILE_HEADER_MAGIC);
            headerbuf.append(
                    RefactoredBackupManagerService.BACKUP_FILE_VERSION); // integer, no trailing \n
            headerbuf.append(mCompress ? "\n1\n" : "\n0\n");

            try {
                // Set up the encryption stage if appropriate, and emit the correct header
                if (encrypting) {
                    finalOutput = emitAesBackupHeader(headerbuf, finalOutput);
                } else {
                    headerbuf.append("none\n");
                }

                byte[] header = headerbuf.toString().getBytes("UTF-8");
                ofstream.write(header);

                // Set up the compression stage feeding into the encryption stage (if any)
                if (mCompress) {
                    Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
                    finalOutput = new DeflaterOutputStream(finalOutput, deflater, true);
                }

                out = finalOutput;
            } catch (Exception e) {
                // Should never happen!
                Slog.e(RefactoredBackupManagerService.TAG, "Unable to emit archive header", e);
                return;
            }

            // Shared storage if requested
            if (mIncludeShared) {
                try {
                    pkg = backupManagerService.getPackageManager().getPackageInfo(
                            RefactoredBackupManagerService.SHARED_BACKUP_AGENT_PACKAGE, 0);
                    backupQueue.add(pkg);
                } catch (NameNotFoundException e) {
                    Slog.e(RefactoredBackupManagerService.TAG,
                            "Unable to find shared-storage backup handler");
                }
            }

            // Now actually run the constructed backup sequence for full backup
            int N = backupQueue.size();
            for (int i = 0; i < N; i++) {
                pkg = backupQueue.get(i);
                if (RefactoredBackupManagerService.DEBUG) {
                    Slog.i(RefactoredBackupManagerService.TAG,
                            "--- Performing full backup for package " + pkg.packageName
                                    + " ---");
                }
                final boolean isSharedStorage =
                        pkg.packageName.equals(
                                RefactoredBackupManagerService.SHARED_BACKUP_AGENT_PACKAGE);

                mBackupEngine = new FullBackupEngine(backupManagerService, out,
                        null, pkg, mIncludeApks, this, Long.MAX_VALUE, mCurrentOpToken);
                sendOnBackupPackage(isSharedStorage ? "Shared storage" : pkg.packageName);

                // Don't need to check preflight result as there is no preflight hook.
                mCurrentTarget = pkg;
                mBackupEngine.backupOnePackage();

                // after the app's agent runs to handle its private filesystem
                // contents, back up any OBB content it has on its behalf.
                if (mIncludeObbs) {
                    boolean obbOkay = obbConnection.backupObbs(pkg, out);
                    if (!obbOkay) {
                        throw new RuntimeException("Failure writing OBB stack for " + pkg);
                    }
                }
            }
            // And for key-value backup if enabled
            if (mKeyValue) {
                for (PackageInfo keyValuePackage : keyValueBackupQueue) {
                    if (RefactoredBackupManagerService.DEBUG) {
                        Slog.i(RefactoredBackupManagerService.TAG,
                                "--- Performing key-value backup for package "
                                        + keyValuePackage.packageName + " ---");
                    }
                    KeyValueAdbBackupEngine kvBackupEngine =
                            new KeyValueAdbBackupEngine(out, keyValuePackage,
                                    backupManagerService,
                                    backupManagerService.getPackageManager(),
                                    backupManagerService.getBaseStateDir(),
                                    backupManagerService.getDataDir());
                    sendOnBackupPackage(keyValuePackage.packageName);
                    kvBackupEngine.backupOnePackage();
                }
            }

            // Done!
            finalizeBackup(out);
        } catch (RemoteException e) {
            Slog.e(RefactoredBackupManagerService.TAG, "App died during full backup");
        } catch (Exception e) {
            Slog.e(RefactoredBackupManagerService.TAG, "Internal exception during full backup", e);
        } finally {
            try {
                if (out != null) {
                    out.flush();
                    out.close();
                }
                mOutputFile.close();
            } catch (IOException e) {
                /* nothing we can do about this */
            }
            synchronized (mLatch) {
                mLatch.set(true);
                mLatch.notifyAll();
            }
            sendEndBackup();
            obbConnection.tearDown();
            if (RefactoredBackupManagerService.DEBUG) {
                Slog.d(RefactoredBackupManagerService.TAG, "Full backup pass complete.");
            }
            backupManagerService.getWakelock().release();
        }
    }

    // BackupRestoreTask methods, used for timeout handling
    @Override
    public void execute() {
        // Unused
    }

    @Override
    public void operationComplete(long result) {
        // Unused
    }

    @Override
    public void handleCancel(boolean cancelAll) {
        final PackageInfo target = mCurrentTarget;
        if (RefactoredBackupManagerService.DEBUG) {
            Slog.w(RefactoredBackupManagerService.TAG, "adb backup cancel of " + target);
        }
        if (target != null) {
            backupManagerService.tearDownAgentAndKill(mCurrentTarget.applicationInfo);
        }
        backupManagerService.removeOperation(mCurrentOpToken);
    }
}
