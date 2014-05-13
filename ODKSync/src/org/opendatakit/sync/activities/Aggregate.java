/*
 * Copyright (C) 2012 University of Washington
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
package org.opendatakit.sync.activities;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.opendatakit.sync.OdkSyncServiceProxy;
import org.opendatakit.sync.R;
import org.opendatakit.sync.SyncPreferences;
import org.opendatakit.sync.SyncUtil;
import org.opendatakit.sync.SynchronizationResult;
import org.opendatakit.sync.TableResult;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

/**
 * An activity for downloading from and uploading to an ODK Aggregate instance.
 * 
 * @author hkworden@gmail.com
 * @author the.dylan.price@gmail.com
 */
public class Aggregate extends Activity {

	private static final String LOGTAG = Aggregate.class.getSimpleName();
	
	public static final String INTENT_KEY_APP_NAME = "appName";
	public static final String INTENT_KEY_TABLE_ID = "tableId";

	private static final String ACCOUNT_TYPE_G = "com.google";
	private static final String URI_FIELD_EMPTY = "http://";

	private static final int AUTHORIZE_ACCOUNT_RESULT_ID = 1;

	private EditText uriField;
	private Spinner accountListSpinner;

	private String appName;
	private AccountManager accountManager;

	private OdkSyncServiceProxy syncProxy;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		appName = getIntent().getStringExtra(INTENT_KEY_APP_NAME);
		if (appName == null) {
			appName = SyncUtil.getDefaultAppName();
		}
		accountManager = AccountManager.get(this);

		syncProxy = new OdkSyncServiceProxy(this);

		setTitle("");
		setContentView(R.layout.aggregate_activity);
		findViewComponents();
		try {
		SyncPreferences prefs = new SyncPreferences(this, appName);
		initializeData(prefs);
		updateButtonsEnabled(prefs);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
	try{	
		SyncPreferences prefs = new SyncPreferences(this, appName);
		updateButtonsEnabled(prefs);
	} catch (IOException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	}
	}

	@Override
	protected void onResume() {
		super.onResume();
		try {
		SyncPreferences prefs = new SyncPreferences(this, appName);
		updateButtonsEnabled(prefs);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void findViewComponents() {
		uriField = (EditText) findViewById(R.id.aggregate_activity_uri_field);
		accountListSpinner = (Spinner) findViewById(R.id.aggregate_activity_account_list_spinner);
	}

	private void initializeData(SyncPreferences prefs) {
		// Add accounts to spinner
		Account[] accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_G);
		List<String> accountNames = new ArrayList<String>(accounts.length);
		for (int i = 0; i < accounts.length; i++)
			accountNames.add(accounts[i].name);

		ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
				android.R.layout.select_dialog_item, accountNames);
		accountListSpinner.setAdapter(adapter);

		// Set saved server url
		String serverUri = prefs.getServerUri();

		if (serverUri == null)
			uriField.setText(URI_FIELD_EMPTY);
		else
			uriField.setText(serverUri);

		// Set chosen account
		String accountName = prefs.getAccount();
		if (accountName != null) {
			int index = accountNames.indexOf(accountName);
			accountListSpinner.setSelection(index);
		}
	}

	void updateButtonsEnabled(SyncPreferences prefs) {
		String accountName = prefs.getAccount();
		String serverUri = prefs.getServerUri();
		boolean haveSettings = (accountName != null) && (serverUri != null);
		boolean authorizeAccount = (accountName != null)
				&& (prefs.getAuthToken() == null);

		boolean restOfButtons = haveSettings && !authorizeAccount;

		findViewById(R.id.aggregate_activity_save_settings_button).setEnabled(
				true);
		findViewById(R.id.aggregate_activity_authorize_account_button)
				.setEnabled(authorizeAccount);
		findViewById(R.id.aggregate_activity_choose_tables_button).setEnabled(
				restOfButtons);
		findViewById(R.id.aggregate_activity_get_table_button).setEnabled(
				restOfButtons);
		findViewById(R.id.aggregate_activity_sync_now_push_button).setEnabled(
				restOfButtons);
		findViewById(R.id.aggregate_activity_sync_now_pull_button).setEnabled(
				restOfButtons);
		// findViewById(R.id.aggregate_activity_sync_using_submit_button).setEnabled(restOfButtons);
	}

	private void saveSettings(SyncPreferences prefs) throws IOException {

		// save fields in preferences
		String uri = uriField.getText().toString();
		if (uri.equals(URI_FIELD_EMPTY))
			uri = null;
		String accountName = (String) accountListSpinner.getSelectedItem();

		prefs.setServerUri(uri);
		prefs.setAccount(accountName);

	}

	AlertDialog.Builder buildOkMessage(String title, String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setCancelable(false);
		builder.setPositiveButton(getString(R.string.ok), null);
		builder.setTitle(title);
		builder.setMessage(message);
		return builder;
	}

	AlertDialog.Builder buildResultMessage(String title,
			SynchronizationResult result) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setCancelable(false);
		builder.setPositiveButton(getString(R.string.ok), null);
		builder.setTitle(title);
		// Now we'll make the message. This should include the contents of the
		// result parameter.
		StringBuilder stringBuilder = new StringBuilder();
		for (int i = 0; i < result.getTableResults().size(); i++) {
			TableResult tableResult = result.getTableResults().get(i);
			stringBuilder.append(SyncUtil.getMessageForTableResult(this,
					tableResult));
			// stringBuilder.append(tableResult.getTableDisplayName() + ": " +
			// SyncUtil.getLocalizedNameForTableResultStatus(this,
			// tableResult.getStatus()));
			// if (tableResult.getStatus() == Status.EXCEPTION) {
			// stringBuilder.append(" with message: " +
			// tableResult.getMessage());
			// }
			if (i < result.getTableResults().size() - 1) {
				// only append if we have a
				stringBuilder.append("\n");
			}
		}
		builder.setMessage(stringBuilder.toString());
		return builder;
	}

	/**
	 * Hooked up to save settings button in aggregate_activity.xml
	 */
	public void onClickSaveSettings(View v) {
		try {
		final SyncPreferences prefs = new SyncPreferences(this, appName);
		// show warning message
		AlertDialog.Builder msg = buildOkMessage(
				getString(R.string.confirm_change_settings),
				getString(R.string.change_settings_warning));

		msg.setPositiveButton(getString(R.string.save), new OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				
				// TODO: IMPORATNT rewrite this interaction
				try {
					saveSettings(prefs);
			
				// SS Oct 15: clear the auth token here.
				// TODO if you change a user you can switch to their privileges
				// without
				// this.
				Log.d(LOGTAG,
						"[onClickSaveSettings][onClick] invalidated authtoken");
				invalidateAuthToken(prefs.getAuthToken(), Aggregate.this,
						appName);
				updateButtonsEnabled(prefs);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
			}
		});

		msg.setNegativeButton(getString(R.string.cancel), null);
		msg.show();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * Hooked up to authorizeAccountButton's onClick in aggregate_activity.xml
	 */
	public void onClickAuthorizeAccount(View v) {
		try {
			SyncPreferences prefs = new SyncPreferences(this, appName);
		Intent i = new Intent(this, AccountInfoActivity.class);
		Account account = new Account(prefs.getAccount(), ACCOUNT_TYPE_G);
		i.putExtra(INTENT_KEY_APP_NAME, appName);
		i.putExtra(AccountInfoActivity.INTENT_EXTRAS_ACCOUNT, account);
		startActivityForResult(i, AUTHORIZE_ACCOUNT_RESULT_ID);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * Hooked to chooseTablesButton's onClick in aggregate_activity.xml
	 */
	public void onClickChooseTables(View v) {
		try {
			SyncPreferences prefs = new SyncPreferences(this, appName);
		Intent i = new Intent(this, AggregateChooseTablesActivity.class);
		i.putExtra(INTENT_KEY_APP_NAME, appName);
		startActivity(i);
		updateButtonsEnabled(prefs);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * Hooked up to downloadTableButton's onClick in aggregate_activity.xml
	 */
	public void onClickDownloadTableFromServer(View v) {
		try {
			SyncPreferences prefs = new SyncPreferences(this, appName);
		Intent i = new Intent(this, AggregateDownloadTableActivity.class);
		i.putExtra(INTENT_KEY_APP_NAME, appName);
		startActivity(i);
		updateButtonsEnabled(prefs);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}

	/**
	 * Hooked to syncNowButton's onClick in aggregate_activity.xml
	 */
	public void onClickSyncNowPush(View v) {
		Log.d(LOGTAG, "in onClickSyncNow");
		// ask whether to sync app files and table-level files
		
		try {
			SyncPreferences prefs = new SyncPreferences(this, appName);
		String accountName = prefs.getAccount();
		Log.e(LOGTAG, "[onClickSyncNow] timestamp: " + System.currentTimeMillis());
		if (accountName == null) {
			Toast.makeText(this, getString(R.string.choose_account),
					Toast.LENGTH_SHORT).show();
		} else {
			 try {
                 syncProxy.pushToServer();
         } catch (RemoteException e) {
                 Log.e(LOGTAG, "Problem with sync command");
         }


		}
		updateButtonsEnabled(prefs);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * Hooked to syncNowButton's onClick in aggregate_activity.xml
	 */
	public void onClickSyncNowPull(View v) {
		Log.d(LOGTAG, "in onClickSyncNow");
		// ask whether to sync app files and table-level files
		try {
			SyncPreferences prefs = new SyncPreferences(this, appName);
			String accountName = prefs.getAccount();
			Log.e(LOGTAG,
					"[onClickSyncNow] timestamp: " + System.currentTimeMillis());
			if (accountName == null) {
				Toast.makeText(this, getString(R.string.choose_account),
						Toast.LENGTH_SHORT).show();
				 try {
                     syncProxy.pushToServer();
             } catch (RemoteException e) {
                     Log.e(LOGTAG, "Problem with sync command");
             }



			}
			
			updateButtonsEnabled(prefs);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		
	}

	public static void invalidateAuthToken(String authToken, Context context,
			String appName) {
		AccountManager.get(context).invalidateAuthToken(ACCOUNT_TYPE_G,
				authToken);
		try {
			SyncPreferences prefs = new SyncPreferences(context, appName);
			prefs.setAuthToken(null);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		try {
			SyncPreferences prefs = new SyncPreferences(this, appName);
		super.onActivityResult(requestCode, resultCode, data);
		updateButtonsEnabled(prefs);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	


}
