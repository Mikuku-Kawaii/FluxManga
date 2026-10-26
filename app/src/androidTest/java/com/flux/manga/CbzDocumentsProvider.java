package com.flux.manga;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Test APK only. Plain Java allows a standalone provider process without the target's Kotlin runtime. */
public class CbzDocumentsProvider extends DocumentsProvider {
    private File fixture() { return new File(getContext().getFilesDir(), "natural-order.cbz"); }
    @Override public boolean onCreate() {
        try (FileOutputStream out = new FileOutputStream(fixture())) { out.write(SyntheticCbz.valid()); return true; }
        catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES});
        cursor.newRow().add(Root.COLUMN_ROOT_ID, "synthetic").add(Root.COLUMN_TITLE, "FluxManga 合成 CBZ")
            .add(Root.COLUMN_DOCUMENT_ID, "root").add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY).add(Root.COLUMN_MIME_TYPES, "*/*");
        return cursor;
    }
    private MatrixCursor cursor(String[] projection) {
        return new MatrixCursor(projection != null ? projection : new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE});
    }
    private void add(MatrixCursor cursor, String id) throws FileNotFoundException {
        if (!id.equals("root") && !id.equals("fixture")) throw new FileNotFoundException();
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id).add(Document.COLUMN_DISPLAY_NAME, id.equals("root") ? "FluxManga 合成 CBZ" : "自然页序合成.cbz")
            .add(Document.COLUMN_MIME_TYPE, id.equals("root") ? Document.MIME_TYPE_DIR : "application/vnd.comicbook+zip")
            .add(Document.COLUMN_FLAGS, 0).add(Document.COLUMN_SIZE, id.equals("root") ? 0L : fixture().length());
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = cursor(projection); add(cursor, id); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) throws FileNotFoundException {
        MatrixCursor cursor = cursor(projection); if (parent.equals("root")) add(cursor, "fixture"); return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!id.equals("fixture") || !mode.equals("r")) throw new FileNotFoundException("Synthetic document is read-only");
        return ParcelFileDescriptor.open(fixture(), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
