package com.ibnrobiul.trackit;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Saves a file straight into the phone's public Downloads folder.
 *  - Android 10+ : uses MediaStore, which needs no storage permission.
 *  - Android 9 and older : asks for the storage permission first, then writes the file.
 */
@CapacitorPlugin(
    name = "DownloadSaver",
    permissions = {
        @Permission(strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE }, alias = "storage")
    }
)
public class DownloadSaverPlugin extends Plugin {

    @PluginMethod
    public void save(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && getPermissionState("storage") != PermissionState.GRANTED) {
            requestPermissionForAlias("storage", call, "storagePermissionCallback");
            return;
        }
        doSave(call);
    }

    @PermissionCallback
    private void storagePermissionCallback(PluginCall call) {
        if (getPermissionState("storage") == PermissionState.GRANTED) {
            doSave(call);
        } else {
            call.reject("Storage permission denied");
        }
    }

    private void doSave(PluginCall call) {
        String filename = call.getString("filename");
        String data = call.getString("data");
        String mime = call.getString("mimeType", "application/octet-stream");

        if (filename == null || data == null) {
            call.reject("filename and data are required");
            return;
        }

        try {
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            String savedName = filename;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = getContext().getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new IOException("Could not create file in Downloads");
                }
                OutputStream out = resolver.openOutputStream(uri);
                if (out == null) {
                    throw new IOException("Could not open file for writing");
                }
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("Could not open the Downloads folder");
                }
                File file = new File(dir, filename);
                int n = 1;
                int dot = filename.lastIndexOf('.');
                String base = dot > 0 ? filename.substring(0, dot) : filename;
                String ext = dot > 0 ? filename.substring(dot) : "";
                while (file.exists()) {
                    savedName = base + " (" + n + ")" + ext;
                    file = new File(dir, savedName);
                    n++;
                }
                FileOutputStream out = new FileOutputStream(file);
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
                MediaScannerConnection.scanFile(
                    getContext(),
                    new String[] { file.getAbsolutePath() },
                    new String[] { mime },
                    null
                );
            }

            JSObject result = new JSObject();
            result.put("saved", true);
            result.put("filename", savedName);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Save failed: " + e.getMessage());
        }
    }
}
