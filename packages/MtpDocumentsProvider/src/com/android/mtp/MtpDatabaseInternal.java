/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.mtp;

import static com.android.mtp.MtpDatabaseConstants.*;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;

import java.util.Objects;

/**
 * Class that provides operations processing SQLite database directly.
 */
class MtpDatabaseInternal {
    private static class OpenHelper extends SQLiteOpenHelper {
        public OpenHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(QUERY_CREATE_DOCUMENTS);
            db.execSQL(QUERY_CREATE_ROOT_EXTRA);
            db.execSQL(QUERY_CREATE_VIEW_ROOTS);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new UnsupportedOperationException();
        }
    }

    private final SQLiteDatabase mDatabase;

    MtpDatabaseInternal(Context context) {
        final OpenHelper helper = new OpenHelper(context);
        mDatabase = helper.getWritableDatabase();
    }

    Cursor queryRoots(String[] columnNames) {
        return mDatabase.query(
                VIEW_ROOTS,
                columnNames,
                COLUMN_ROW_STATE + " IN (?, ?)",
                strings(ROW_STATE_VALID, ROW_STATE_INVALIDATED),
                null,
                null,
                null);
    }

    Cursor queryRootDocuments(String[] columnNames) {
        return mDatabase.query(
                TABLE_DOCUMENTS,
                columnNames,
                COLUMN_ROW_STATE + " IN (?, ?)",
                strings(ROW_STATE_VALID, ROW_STATE_INVALIDATED),
                null,
                null,
                null);
    }

    Cursor queryChildDocuments(String[] columnNames, String parentDocumentId) {
        return mDatabase.query(
                TABLE_DOCUMENTS,
                columnNames,
                COLUMN_ROW_STATE + " IN (?, ?) AND " + COLUMN_PARENT_DOCUMENT_ID + " = ?",
                strings(ROW_STATE_VALID, ROW_STATE_INVALIDATED, parentDocumentId),
                null,
                null,
                null);
    }

    /**
     * Starts adding new documents.
     * The methods decides mapping mode depends on if all documents under the given parent have MTP
     * identifier or not. If all the documents have MTP identifier, it uses the identifier to find
     * a corresponding existing row. Otherwise it does heuristic.
     *
     * @param selection Query matches valid documents.
     * @param arg Argument for selection.
     * @return Mapping mode.
     */
    int startAddingDocuments(String selection, String arg) {
        mDatabase.beginTransaction();
        try {
            // Delete all pending rows.
            deleteDocumentsAndRoots(
                    selection + " AND " + COLUMN_ROW_STATE + "=?", strings(arg, ROW_STATE_PENDING));

            // Set all documents as invalidated.
            final ContentValues values = new ContentValues();
            values.put(COLUMN_ROW_STATE, ROW_STATE_INVALIDATED);
            mDatabase.update(TABLE_DOCUMENTS, values, selection, new String[] { arg });

            // If we have rows that does not have MTP identifier, do heuristic mapping by name.
            final boolean useNameForResolving = DatabaseUtils.queryNumEntries(
                    mDatabase,
                    TABLE_DOCUMENTS,
                    selection + " AND " + COLUMN_STORAGE_ID + " IS NULL",
                    new String[] { arg }) > 0;
            mDatabase.setTransactionSuccessful();
            return useNameForResolving ? MAP_BY_NAME : MAP_BY_MTP_IDENTIFIER;
        } finally {
            mDatabase.endTransaction();
        }
    }

    /**
     * Puts the documents into the database.
     * If the mapping mode is not heuristic, it just adds the rows to the database or updates the
     * existing rows with the new values. If the mapping mode is heuristic, it adds some new rows as
     * 'pending' state when that rows may be corresponding to existing 'invalidated' rows. Then
     * {@link #stopAddingDocuments(String, String, String)} turns the pending rows into 'valid'
     * rows.
     *
     * @param valuesList Values that are stored in the database.
     * @param selection SQL where closure to select rows that shares the same parent.
     * @param arg Argument for selection SQL.
     * @param heuristic Whether the mapping mode is heuristic.
     * @return List of Document ID inserted to the table.
     */
    long[] putDocuments(
            ContentValues[] valuesList,
            String selection,
            String arg,
            boolean heuristic,
            String mappingKey) {
        mDatabase.beginTransaction();
        try {
            final long[] documentIds = new long[valuesList.length];
            int i = 0;
            for (final ContentValues values : valuesList) {
                final Cursor candidateCursor = mDatabase.query(
                        TABLE_DOCUMENTS,
                        strings(Document.COLUMN_DOCUMENT_ID),
                        selection + " AND " +
                        COLUMN_ROW_STATE + "=? AND " +
                        mappingKey + "=?",
                        strings(arg, ROW_STATE_INVALIDATED, values.getAsString(mappingKey)),
                        null,
                        null,
                        null,
                        "1");
                final long rowId;
                if (candidateCursor.getCount() == 0) {
                    rowId = mDatabase.insert(TABLE_DOCUMENTS, null, values);
                } else if (!heuristic) {
                    candidateCursor.moveToNext();
                    final String documentId = candidateCursor.getString(0);
                    rowId = mDatabase.update(
                            TABLE_DOCUMENTS, values, SELECTION_DOCUMENT_ID, strings(documentId));
                } else {
                    values.put(COLUMN_ROW_STATE, ROW_STATE_PENDING);
                    rowId = mDatabase.insert(TABLE_DOCUMENTS, null, values);
                }
                // Document ID is a primary integer key of the table. So the returned row
                // IDs should be same with the document ID.
                documentIds[i++] = rowId;
                candidateCursor.close();
            }

            mDatabase.setTransactionSuccessful();
            return documentIds;
        } finally {
            mDatabase.endTransaction();
        }
    }

    void putRootExtra(ContentValues values) {
        mDatabase.replace(TABLE_ROOT_EXTRA, null, values);
    }

    /**
     * Maps 'pending' document and 'invalidated' document that shares the same column of groupKey.
     * If the database does not find corresponding 'invalidated' document, it just removes
     * 'invalidated' document from the database.
     * @param selection Query to select rows for resolving.
     * @param arg Argument for selection SQL.
     * @param groupKey Column name used to find corresponding rows.
     */
    void stopAddingDocuments(String selection, String arg, String groupKey) {
        mDatabase.beginTransaction();
        try {
            // Get 1-to-1 mapping of invalidated document and pending document.
            final String invalidatedIdQuery = createStateFilter(
                    ROW_STATE_INVALIDATED, Document.COLUMN_DOCUMENT_ID);
            final String pendingIdQuery = createStateFilter(
                    ROW_STATE_PENDING, Document.COLUMN_DOCUMENT_ID);
            // SQL should be like:
            // SELECT group_concat(CASE WHEN raw_state = 1 THEN document_id ELSE NULL END),
            //        group_concat(CASE WHEN raw_state = 2 THEN document_id ELSE NULL END)
            // WHERE device_id = ? AND parent_document_id IS NULL
            // GROUP BY display_name
            // HAVING count(CASE WHEN raw_state = 1 THEN document_id ELSE NULL END) = 1 AND
            //        count(CASE WHEN raw_state = 2 THEN document_id ELSE NULL END) = 1
            final Cursor mergingCursor = mDatabase.query(
                    TABLE_DOCUMENTS,
                    new String[] {
                            "group_concat(" + invalidatedIdQuery + ")",
                            "group_concat(" + pendingIdQuery + ")"
                    },
                    selection,
                    strings(arg),
                    groupKey,
                    "count(" + invalidatedIdQuery + ") = 1 AND count(" + pendingIdQuery + ") = 1",
                    null);

            final ContentValues values = new ContentValues();
            while (mergingCursor.moveToNext()) {
                final String invalidatedId = mergingCursor.getString(0);
                final String pendingId = mergingCursor.getString(1);

                // Obtain the new values including the latest object handle from mapping row.
                getFirstRow(
                        TABLE_DOCUMENTS,
                        SELECTION_DOCUMENT_ID,
                        new String[] { pendingId },
                        values);
                values.remove(Document.COLUMN_DOCUMENT_ID);
                values.put(COLUMN_ROW_STATE, ROW_STATE_VALID);
                mDatabase.update(
                        TABLE_DOCUMENTS,
                        values,
                        SELECTION_DOCUMENT_ID,
                        new String[] { invalidatedId });

                getFirstRow(
                        TABLE_ROOT_EXTRA,
                        SELECTION_ROOT_ID,
                        new String[] { pendingId },
                        values);
                if (values.size() > 0) {
                    values.remove(Root.COLUMN_ROOT_ID);
                    mDatabase.update(
                            TABLE_ROOT_EXTRA,
                            values,
                            SELECTION_ROOT_ID,
                            new String[] { invalidatedId });
                }

                // Delete 'pending' row.
                deleteDocumentsAndRoots(SELECTION_DOCUMENT_ID, new String[] { pendingId });
            }
            mergingCursor.close();

            // Delete all invalidated rows that cannot be mapped.
            deleteDocumentsAndRoots(
                    COLUMN_ROW_STATE + " = ? AND " + selection,
                    strings(ROW_STATE_INVALIDATED, arg));

            // The database cannot find old document ID for the pending rows.
            // Turn the all pending rows into valid state, which means the rows become to be
            // valid with new document ID.
            values.clear();
            values.put(COLUMN_ROW_STATE, ROW_STATE_VALID);
            mDatabase.update(
                    TABLE_DOCUMENTS,
                    values,
                    COLUMN_ROW_STATE + " = ? AND " + selection,
                    strings(ROW_STATE_PENDING, arg));
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }
    }

    /**
     * Clears MTP related identifier.
     * It clears MTP's object handle and storage ID that are not stable over MTP sessions and mark
     * the all documents as 'invalidated'. It also remove 'pending' rows as adding is cancelled
     * now.
     */
    void clearMapping() {
        mDatabase.beginTransaction();
        try {
            deleteDocumentsAndRoots(COLUMN_ROW_STATE + " = ?", strings(ROW_STATE_PENDING));
            final ContentValues values = new ContentValues();
            values.putNull(COLUMN_OBJECT_HANDLE);
            values.putNull(COLUMN_STORAGE_ID);
            values.put(COLUMN_ROW_STATE, ROW_STATE_INVALIDATED);
            mDatabase.update(TABLE_DOCUMENTS, values, null, null);
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }
    }

    void beginTransaction() {
        mDatabase.beginTransaction();
    }

    void setTransactionSuccessful() {
        mDatabase.setTransactionSuccessful();
    }

    void endTransaction() {
        mDatabase.endTransaction();
    }

    /**
     * Deletes a document, and its root information if the document is a root document.
     * @param selection Query to select documents.
     * @param args Arguments for selection.
     */
    private void deleteDocumentsAndRoots(String selection, String[] args) {
        mDatabase.beginTransaction();
        try {
            mDatabase.delete(
                    TABLE_ROOT_EXTRA,
                    Root.COLUMN_ROOT_ID + " IN (" + SQLiteQueryBuilder.buildQueryString(
                            false,
                            TABLE_DOCUMENTS,
                            new String[] { Document.COLUMN_DOCUMENT_ID },
                            selection,
                            null,
                            null,
                            null,
                            null) + ")",
                    args);
            mDatabase.delete(TABLE_DOCUMENTS, selection, args);
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }
    }

    /**
     * Obtains values of the first row for the query.
     * @param values ContentValues that the values are stored to.
     * @param table Target table.
     * @param selection Query to select rows.
     * @param args Argument for query.
     */
    private void getFirstRow(String table, String selection, String[] args, ContentValues values) {
        values.clear();
        final Cursor cursor = mDatabase.query(table, null, selection, args, null, null, null, "1");
        if (cursor.getCount() == 0) {
            return;
        }
        cursor.moveToNext();
        DatabaseUtils.cursorRowToContentValues(cursor, values);
        cursor.close();
    }

    /**
     * Gets SQL expression that represents the given value or NULL depends on the row state.
     * @param state Expected row state.
     * @param a SQL value.
     * @return Expression that represents a if the row state is expected one, and represents NULL
     *     otherwise.
     */
    private static String createStateFilter(int state, String a) {
        return "CASE WHEN " + COLUMN_ROW_STATE + " = " + Integer.toString(state) +
                " THEN " + a + " ELSE NULL END";
    }

    /**
     * Converts values into string array.
     * @param args Values converted into string array.
     * @return String array.
     */
    private static String[] strings(Object... args) {
        final String[] results = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            results[i] = Objects.toString(args[i]);
        }
        return results;
    }
}