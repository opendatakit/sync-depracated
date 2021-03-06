/*
 * Copyright (C) 2014 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.conflict.activities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opendatakit.aggregate.odktables.rest.ElementType;
import org.opendatakit.aggregate.odktables.rest.KeyValueStoreConstants;
import org.opendatakit.aggregate.odktables.rest.SavepointTypeManipulator;
import org.opendatakit.common.android.data.ColumnDefinition;
import org.opendatakit.common.android.data.KeyValueStoreEntry;
import org.opendatakit.common.android.data.UserTable;
import org.opendatakit.common.android.data.UserTable.Row;
import org.opendatakit.common.android.database.DatabaseFactory;
import org.opendatakit.common.android.provider.DataTableColumns;
import org.opendatakit.common.android.utilities.NameUtil;
import org.opendatakit.common.android.utilities.ODKDataUtils;
import org.opendatakit.common.android.utilities.ODKDatabaseUtils;
import org.opendatakit.common.android.utilities.TableUtil;
import org.opendatakit.common.android.utilities.WebLogger;
import org.opendatakit.sync.R;
import org.opendatakit.sync.views.components.ConcordantColumn;
import org.opendatakit.sync.views.components.ConflictColumn;
import org.opendatakit.sync.views.components.ConflictResolutionListAdapter;
import org.opendatakit.sync.views.components.ConflictResolutionListAdapter.Resolution;
import org.opendatakit.sync.views.components.ConflictResolutionListAdapter.Section;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * Activity for resolving the conflicts in a row. This is the native version,
 * which presents a UI and does not support HTML or js rules.
 * 
 * @author sudar.sam@gmail.com
 *
 */
public class CheckpointResolutionRowActivity extends ListActivity implements
    ConflictResolutionListAdapter.UICallbacks {

  private static final String TAG = CheckpointResolutionRowActivity.class.getSimpleName();

  public static final String INTENT_KEY_ROW_ID = "rowId";

  private static final String BUNDLE_KEY_SHOWING_LOCAL_DIALOG = "showingLocalDialog";
  private static final String BUNDLE_KEY_SHOWING_SERVER_DIALOG = "showingServerDialog";
  private static final String BUNDLE_KEY_VALUE_KEYS = "valueValueKeys";
  private static final String BUNDLE_KEY_CHOSEN_VALUES = "chosenValues";
  private static final String BUNDLE_KEY_RESOLUTION_KEYS = "resolutionKeys";
  private static final String BUNDLE_KEY_RESOLUTION_VALUES = "resolutionValues";

  private ConflictResolutionListAdapter mAdapter;
  private String mAppName;
  private String mTableId;
  private ArrayList<ColumnDefinition> mOrderedDefns;

  private String mRowId;
  UserTable mConflictTable;

  private Button mButtonTakeOldest;
  private Button mButtonTakeNewest;
  private List<ConflictColumn> mConflictColumns;

  /**
   * The message to the user as to why they're getting extra options. Will be
   * either thing to the effect of "someone has deleted something you've
   * changed", or "you've deleted something someone has changed". They'll then
   * have to choose either to delete or to go ahead and actually restore and
   * then resolve it.
   */
  private TextView mTextViewCheckpointOverviewMessage;

  private boolean mIsShowingTakeNewestDialog;
  private boolean mIsShowingTakeOldestDialog;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    mAppName = getIntent().getStringExtra(Constants.APP_NAME);
    if (mAppName == null) {
      mAppName = Constants.DEFAULT_APP_NAME;
    }
    this.setContentView(R.layout.checkpoint_resolution_row_activity);
    this.mTextViewCheckpointOverviewMessage = (TextView) findViewById(R.id.checkpoint_overview_message);
    this.mButtonTakeOldest = (Button) findViewById(R.id.take_oldest);
    this.mButtonTakeNewest = (Button) findViewById(R.id.take_newest);

    mTableId = getIntent().getStringExtra(Constants.TABLE_ID);
    this.mRowId = getIntent().getStringExtra(INTENT_KEY_ROW_ID);

    Map<String, String> persistedDisplayNames = new HashMap<String, String>();
    {
      SQLiteDatabase db = null;
      try {
        db = DatabaseFactory.get().getDatabase(this, mAppName);
        mOrderedDefns = TableUtil.get().getColumnDefinitions(db, mAppName, mTableId);

        List<KeyValueStoreEntry> columnDisplayNames = ODKDatabaseUtils.get().getDBTableMetadata(db,
            mTableId, KeyValueStoreConstants.PARTITION_COLUMN, null,
            KeyValueStoreConstants.COLUMN_DISPLAY_NAME);

        for (KeyValueStoreEntry e : columnDisplayNames) {
          try {
            ColumnDefinition.find(mOrderedDefns, e.aspect);
            persistedDisplayNames.put(e.aspect, e.value);
          } catch (IllegalArgumentException ex) {
            // ignore
          }
        }

        mConflictTable = ODKDatabaseUtils.get().rawSqlQuery(db, mAppName, mTableId, mOrderedDefns,
            DataTableColumns.ID + "=?", new String[] { mRowId }, null, null,
            DataTableColumns.SAVEPOINT_TIMESTAMP, "ASC");
      } finally {
        db.close();
      }
    }

    if (mConflictTable.getNumberOfRows() == 0) {
      // another process deleted this row?
      setResult(RESULT_OK);
      finish();
      return;
    }

    // the first row is the oldest -- it should be a COMPLETE or INCOMPLETE row
    // if it isn't, then this is a new row and the option is to delete it or
    // save as incomplete. Otherwise, it is to roll back or update to
    // incomplete.

    Row rowStarting = mConflictTable.getRowAtIndex(0);
    String type = rowStarting.getRawDataOrMetadataByElementKey(DataTableColumns.SAVEPOINT_TYPE);
    boolean deleteEntirely = (type == null || type.length() == 0);

    if (!deleteEntirely) {
      if (mConflictTable.getNumberOfRows() == 1
          && (SavepointTypeManipulator.isComplete(type) || SavepointTypeManipulator
              .isIncomplete(type))) {
        // something else seems to have resolved this?
        setResult(RESULT_OK);
        finish();
        return;
      }
    }

    Row rowEnding = mConflictTable.getRowAtIndex(mConflictTable.getNumberOfRows() - 1);
    //
    // And now we need to construct up the adapter.

    // There are several things to do be aware of. We need to get all the
    // section headings, which will be the column names. We also need to get
    // all the values which are in conflict, as well as those that are not.
    // We'll present them in user-defined order, as they may have set up the
    // useful information together.
    // This will be the number of rows down we are in the adapter. Each
    // heading and each cell value gets its own row. Columns in conflict get
    // two, as we'll need to display each one to the user.
    int adapterOffset = 0;
    List<Section> sections = new ArrayList<Section>();
    this.mConflictColumns = new ArrayList<ConflictColumn>();
    List<ConcordantColumn> noConflictColumns = new ArrayList<ConcordantColumn>();
    for (ColumnDefinition cd : mOrderedDefns) {
      if (!cd.isUnitOfRetention()) {
        continue;
      }
      String elementKey = cd.getElementKey();
      ElementType elementType = cd.getType();
      String columnDisplayName = persistedDisplayNames.get(elementKey);
      if (columnDisplayName != null) {
        columnDisplayName = ODKDataUtils.getLocalizedDisplayName(columnDisplayName);
      } else {
        columnDisplayName = NameUtil.constructSimpleDisplayName(elementKey);
      }
      Section newSection = new Section(adapterOffset, columnDisplayName);
      ++adapterOffset;
      sections.add(newSection);
      String localRawValue = rowEnding.getRawDataOrMetadataByElementKey(elementKey);
      String localDisplayValue = rowEnding
          .getDisplayTextOfData(this, elementType, elementKey, true);
      String serverRawValue = rowStarting.getRawDataOrMetadataByElementKey(elementKey);
      String serverDisplayValue = rowStarting.getDisplayTextOfData(this, elementType, elementKey,
          true);
      if (deleteEntirely || (localRawValue == null && serverRawValue == null)
          || (localRawValue != null && localRawValue.equals(serverRawValue))) {
        // TODO: this doesn't compare actual equality of blobs if their display
        // text is the same.
        // We only want to display a single row, b/c there are no choices to
        // be made by the user.
        ConcordantColumn concordance = new ConcordantColumn(adapterOffset, localDisplayValue);
        noConflictColumns.add(concordance);
        ++adapterOffset;
      } else {
        // We need to display both the server and local versions.
        ConflictColumn conflictColumn = new ConflictColumn(adapterOffset, elementKey,
            localRawValue, localDisplayValue, serverRawValue, serverDisplayValue);
        ++adapterOffset;
        mConflictColumns.add(conflictColumn);
      }
    }
    // Now that we have the appropriate lists, we need to construct the
    // adapter that will display the information.
    this.mAdapter = new ConflictResolutionListAdapter(this.getActionBar().getThemedContext(),
        mAppName, this, sections, noConflictColumns, mConflictColumns);
    this.setListAdapter(mAdapter);
    // Here we'll handle the cases of whether or not rows were deleted. There
    // are three cases to consider:
    // 1) both rows were updated, neither is deleted. This is the normal case
    // 2) the server row was deleted, the local was updated (thus a conflict)
    // 3) the local was deleted, the server was updated (thus a conflict)
    // To Figure this out we'll first need the state of each version.
    // Note that these calls should never return nulls, as whenever a row is in
    // conflict, there should be a conflict type. Therefore if we throw an
    // error that is fine, as we've violated an invariant.

    if (!deleteEntirely) {
      this.mButtonTakeNewest
          .setOnClickListener(new DiscardOlderValuesAndMarkNewestAsIncompleteRowClickListener());
      this.mButtonTakeOldest
          .setOnClickListener(new DiscardNewerValuesAndRetainOldestInOriginalStateRowClickListener());
      if (SavepointTypeManipulator.isComplete(type)) {
        this.mTextViewCheckpointOverviewMessage
            .setText(getString(R.string.checkpoint_restore_complete_or_take_newest));
        this.mButtonTakeOldest.setText(getString(R.string.checkpoint_take_oldest_finalized));
      } else {
        this.mTextViewCheckpointOverviewMessage
            .setText(getString(R.string.checkpoint_restore_incomplete_or_take_newest));
        this.mButtonTakeOldest.setText(getString(R.string.checkpoint_take_oldest_incomplete));
      }
      mAdapter.setConflictColumnsEnabled(false);
      mAdapter.notifyDataSetChanged();
    } else {
      this.mButtonTakeNewest
          .setOnClickListener(new DiscardOlderValuesAndMarkNewestAsIncompleteRowClickListener());
      this.mButtonTakeOldest.setOnClickListener(new DiscardAllValuesAndDeleteRowClickListener());
      this.mTextViewCheckpointOverviewMessage
          .setText(getString(R.string.checkpoint_remove_or_take_newest));
      this.mButtonTakeOldest.setText(getString(R.string.checkpoint_take_oldest_remove));
    }

    this.onDecisionMade();
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.opendatakit.tables.views.components.ConflictResolutionListAdapter.
   * UICallbacks#onDecisionMade(boolean)
   */
  @Override
  public void onDecisionMade() {
    // set the listview enabled in case it'd been down due to deletion
    // resolution.
    mAdapter.setConflictColumnsEnabled(false);
    mAdapter.notifyDataSetChanged();
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putBoolean(BUNDLE_KEY_SHOWING_LOCAL_DIALOG, mIsShowingTakeNewestDialog);
    outState.putBoolean(BUNDLE_KEY_SHOWING_SERVER_DIALOG, mIsShowingTakeOldestDialog);
    // We also need to get the chosen values and decisions and save them so
    // that we don't lose information if they rotate the screen.
    Map<String, String> chosenValuesMap = mAdapter.getResolvedValues();
    Map<String, Resolution> userResolutions = mAdapter.getResolutions();
    if (chosenValuesMap.size() != userResolutions.size()) {
      WebLogger.getLogger(mAppName).e(
          TAG,
          "[onSaveInstanceState] chosen values and user resolutions"
              + " are not the same size. This should be impossible, so not " + "saving state.");
      return;
    }
    String[] valueKeys = new String[chosenValuesMap.size()];
    String[] chosenValues = new String[chosenValuesMap.size()];
    String[] resolutionKeys = new String[userResolutions.size()];
    String[] resolutionValues = new String[userResolutions.size()];
    int i = 0;
    for (Map.Entry<String, String> valueEntry : chosenValuesMap.entrySet()) {
      valueKeys[i] = valueEntry.getKey();
      chosenValues[i] = valueEntry.getValue();
      ++i;
      ;
    }
    i = 0;
    for (Map.Entry<String, Resolution> resolutionEntry : userResolutions.entrySet()) {
      resolutionKeys[i] = resolutionEntry.getKey();
      resolutionValues[i] = resolutionEntry.getValue().name();
      ++i;
    }
    outState.putStringArray(BUNDLE_KEY_VALUE_KEYS, valueKeys);
    outState.putStringArray(BUNDLE_KEY_CHOSEN_VALUES, chosenValues);
    outState.putStringArray(BUNDLE_KEY_RESOLUTION_KEYS, resolutionKeys);
    outState.putStringArray(BUNDLE_KEY_RESOLUTION_VALUES, resolutionValues);
  }

  @Override
  protected void onRestoreInstanceState(Bundle savedInstanceState) {
    super.onRestoreInstanceState(savedInstanceState);
    WebLogger.getLogger(mAppName).i(TAG, "onRestoreInstanceState");
    if (savedInstanceState.containsKey(BUNDLE_KEY_SHOWING_LOCAL_DIALOG)) {
      boolean wasShowingLocal = savedInstanceState.getBoolean(BUNDLE_KEY_SHOWING_LOCAL_DIALOG);
      if (wasShowingLocal)
        this.mButtonTakeOldest.performClick();
    }
    if (savedInstanceState.containsKey(BUNDLE_KEY_SHOWING_SERVER_DIALOG)) {
      boolean wasShowingServer = savedInstanceState.getBoolean(BUNDLE_KEY_SHOWING_SERVER_DIALOG);
      if (wasShowingServer)
        this.mButtonTakeNewest.performClick();
    }
    String[] valueKeys = savedInstanceState.getStringArray(BUNDLE_KEY_VALUE_KEYS);
    String[] chosenValues = savedInstanceState.getStringArray(BUNDLE_KEY_CHOSEN_VALUES);
    String[] resolutionKeys = savedInstanceState.getStringArray(BUNDLE_KEY_RESOLUTION_KEYS);
    String[] resolutionValues = savedInstanceState.getStringArray(BUNDLE_KEY_RESOLUTION_VALUES);
    if (valueKeys != null) {
      // Then we know that we should have the chosenValues as well, or else
      // there is trouble. We're not doing a null check here, but if we didn't
      // get it then we know there is an error. We'll throw a null pointer
      // exception, but that is better than restoring bad state.
      // Same thing goes for the resolution keys. Those and the map should
      // always go together.
      Map<String, String> chosenValuesMap = new HashMap<String, String>();
      for (int i = 0; i < valueKeys.length; i++) {
        chosenValuesMap.put(valueKeys[i], chosenValues[i]);
      }
      Map<String, Resolution> userResolutions = new HashMap<String, Resolution>();
      for (int i = 0; i < resolutionKeys.length; i++) {
        userResolutions.put(resolutionKeys[i], Resolution.valueOf(resolutionValues[i]));
      }
      mAdapter.setRestoredState(chosenValuesMap, userResolutions);
    }
    // And finally, call this to make sure we update the button as appropriate.
    WebLogger.getLogger(mAppName).i(TAG, "going to call onDecisionMade");
    this.onDecisionMade();

  }

  private class DiscardOlderValuesAndMarkNewestAsIncompleteRowClickListener implements
      View.OnClickListener {

    @Override
    public void onClick(View v) {
      AlertDialog.Builder builder = new AlertDialog.Builder(CheckpointResolutionRowActivity.this
          .getActionBar().getThemedContext());
      builder.setMessage(getString(R.string.checkpoint_take_newest_warning));
      builder.setPositiveButton(getString(R.string.yes), new DialogInterface.OnClickListener() {

        @Override
        public void onClick(DialogInterface dialog, int which) {
          mIsShowingTakeNewestDialog = false;

          SQLiteDatabase db = null;
          try {
            db = DatabaseFactory.get().getDatabase(CheckpointResolutionRowActivity.this, mAppName);
            db.beginTransaction();
            ODKDatabaseUtils.get().saveAsIncompleteMostRecentCheckpointDataInDBTableWithId(db,
                mTableId, mRowId);
            db.setTransactionSuccessful();
          } finally {
            if (db != null) {
              db.endTransaction();
              db.close();
            }
          }
          CheckpointResolutionRowActivity.this.finish();
          WebLogger.getLogger(mAppName).d(TAG, "update to checkpointed version");
        }
      });
      builder.setCancelable(true);
      builder.setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {

        @Override
        public void onClick(DialogInterface dialog, int which) {
          dialog.cancel();
        }
      });
      builder.setOnCancelListener(new OnCancelListener() {

        @Override
        public void onCancel(DialogInterface dialog) {
          mIsShowingTakeNewestDialog = false;
        }
      });
      mIsShowingTakeNewestDialog = true;
      builder.create().show();
    }

  }

  private class DiscardAllValuesAndDeleteRowClickListener implements View.OnClickListener {

    @Override
    public void onClick(View v) {
      // We should do a popup.
      AlertDialog.Builder builder = new AlertDialog.Builder(CheckpointResolutionRowActivity.this
          .getActionBar().getThemedContext());
      builder.setMessage(getString(R.string.checkpoint_delete_warning));
      builder.setPositiveButton(getString(R.string.yes), new DialogInterface.OnClickListener() {

        @Override
        public void onClick(DialogInterface dialog, int which) {
          mIsShowingTakeOldestDialog = false;

          SQLiteDatabase db = null;
          try {
            db = DatabaseFactory.get().getDatabase(CheckpointResolutionRowActivity.this, mAppName);
            db.beginTransaction();
            ODKDatabaseUtils.get().deleteCheckpointRowsWithId(db, mAppName, mTableId, mRowId);
            db.setTransactionSuccessful();
          } finally {
            if (db != null) {
              db.endTransaction();
              db.close();
            }
          }

          CheckpointResolutionRowActivity.this.finish();
          WebLogger.getLogger(mAppName).d(TAG, "deleted all versions");
        }
      });
      builder.setCancelable(true);
      builder.setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {

        @Override
        public void onClick(DialogInterface dialog, int which) {
          dialog.cancel();
        }
      });
      builder.setOnCancelListener(new OnCancelListener() {

        @Override
        public void onCancel(DialogInterface dialog) {
          mIsShowingTakeOldestDialog = false;
          dialog.dismiss();
        }
      });
      mIsShowingTakeOldestDialog = true;
      builder.create().show();
    }
  }

  private class DiscardNewerValuesAndRetainOldestInOriginalStateRowClickListener implements
      View.OnClickListener {

    @Override
    public void onClick(View v) {
      AlertDialog.Builder builder = new AlertDialog.Builder(CheckpointResolutionRowActivity.this
          .getActionBar().getThemedContext());
      builder.setMessage(getString(R.string.checkpoint_take_oldest_warning));
      builder.setPositiveButton(getString(R.string.yes), new DialogInterface.OnClickListener() {

        @Override
        public void onClick(DialogInterface dialog, int which) {
          mIsShowingTakeOldestDialog = false;

          SQLiteDatabase db = null;
          try {
            db = DatabaseFactory.get().getDatabase(CheckpointResolutionRowActivity.this, mAppName);
            db.beginTransaction();
            ODKDatabaseUtils.get().deleteCheckpointRowsWithId(db, mAppName, mTableId, mRowId);
            db.setTransactionSuccessful();
          } finally {
            if (db != null) {
              db.endTransaction();
              db.close();
            }
          }
          CheckpointResolutionRowActivity.this.finish();
          WebLogger.getLogger(mAppName).d(TAG, "delete the checkpointed version");
        }
      });
      builder.setCancelable(true);
      builder.setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {

        @Override
        public void onClick(DialogInterface dialog, int which) {
          dialog.cancel();
        }
      });
      builder.setOnCancelListener(new OnCancelListener() {

        @Override
        public void onCancel(DialogInterface dialog) {
          mIsShowingTakeOldestDialog = false;
          dialog.dismiss();
        }
      });
      mIsShowingTakeOldestDialog = true;
      builder.create().show();
    }

  }

}
