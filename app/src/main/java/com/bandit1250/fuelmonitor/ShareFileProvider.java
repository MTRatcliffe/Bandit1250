package com.bandit1250.fuelmonitor;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Minimal read-only ContentProvider for sharing completed ECU .bin files.
 * Avoids file:// URIs and keeps the rest of the app dependency-free.
 */
public final class ShareFileProvider extends ContentProvider {
    public static Uri uriFor(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".files")
                .appendPath(file.getName())
                .build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null) {
            throw new FileNotFoundException("Provider context unavailable");
        }

        String name = uri.getLastPathSegment();

        if (name == null || name.isEmpty() ||
                name.contains("/") || name.contains("\\")) {
            throw new FileNotFoundException("Invalid file name");
        }

        File base = new File(getContext().getFilesDir(), "ecu_dumps");
        File target = new File(base, name);

        try {
            String basePath = base.getCanonicalPath() + File.separator;
            String targetPath = target.getCanonicalPath();

            if (!targetPath.startsWith(basePath)) {
                throw new FileNotFoundException("Invalid path");
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }

        if (!target.isFile()) {
            throw new FileNotFoundException("File not found");
        }

        return target;
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Read-only provider");
        }

        return ParcelFileDescriptor.open(
                resolve(uri),
                ParcelFileDescriptor.MODE_READ_ONLY
        );
    }

    @Override public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        try {
            File file = resolve(uri);
            MatrixCursor cursor = new MatrixCursor(
                    new String[] {
                            OpenableColumns.DISPLAY_NAME,
                            OpenableColumns.SIZE
                    }
            );
            cursor.addRow(new Object[] {file.getName(), file.length()});
            return cursor;
        } catch (FileNotFoundException e) {
            return new MatrixCursor(
                    new String[] {
                            OpenableColumns.DISPLAY_NAME,
                            OpenableColumns.SIZE
                    }
            );
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs
    ) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override public int delete(
            Uri uri,
            String selection,
            String[] selectionArgs
    ) {
        throw new UnsupportedOperationException("Read-only provider");
    }
}
