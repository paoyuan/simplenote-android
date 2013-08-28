package com.automattic.simplenote;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.SearchView;

import com.automattic.simplenote.models.Note;
import com.automattic.simplenote.models.Tag;
import com.automattic.simplenote.utils.PrefUtils;
import com.automattic.simplenote.utils.UndoBarController;
import com.google.analytics.tracking.android.EasyTracker;
import com.google.analytics.tracking.android.Tracker;
import com.simperium.Simperium;
import com.simperium.client.Bucket;
import com.simperium.client.LoginActivity;
import com.simperium.client.Query;
import com.simperium.client.User;

import net.hockeyapp.android.CrashManager;
import net.hockeyapp.android.UpdateManager;

import java.util.Calendar;

/**
 * An activity representing a list of Notes.
 * <p/>
 * The activity makes heavy use of fragments. The list of items is a
 * {@link NoteListFragment} and the item details (if present) is a
 * {@link NoteEditorFragment}.
 * <p/>
 * This activity also implements the required {@link NoteListFragment.Callbacks}
 * interface to listen for item selections.
 */
public class NotesActivity extends Activity implements
        NoteListFragment.Callbacks, ActionBar.OnNavigationListener,
        User.AuthenticationListener, Simperium.OnUserCreatedListener, UndoBarController.UndoListener,
        FragmentManager.OnBackStackChangedListener, Bucket.Listener<Note> {

    private boolean mIsLargeScreen, mIsLandscape;
    private UndoBarController mUndoBarController;
    private SearchView mSearchView;
    private MenuItem mSearchMenuItem;
    private NoteListFragment mNoteListFragment;
    private NoteEditorFragment mNoteEditorFragment;
    private Note mCurrentNote;
    private Bucket<Note> mNotesBucket;
    private Bucket<Tag> mTagsBucket;
    private int TRASH_SELECTED_ID = 1;
    private ActionBar mActionBar;

    public static String TAG_NOTE_LIST = "noteList";
    public static String TAG_NOTE_EDITOR = "noteEditor";

    // Google Analytics tracker
    private Tracker mTracker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Simplenote currentApp = (Simplenote) getApplication();
        mNotesBucket = currentApp.getNotesBucket();
        mTagsBucket = currentApp.getTagsBucket();
        setContentView(R.layout.activity_notes);

        EasyTracker.getInstance().activityStart(this); // Add this method.
        mTracker = EasyTracker.getTracker();

        if (savedInstanceState == null) {
            mNoteListFragment = new NoteListFragment();
            FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
            fragmentTransaction.add(R.id.noteFragmentContainer, mNoteListFragment, TAG_NOTE_LIST);
            fragmentTransaction.commit();
        } else {
            mNoteListFragment = (NoteListFragment) getFragmentManager().findFragmentByTag(TAG_NOTE_LIST);
        }


        Configuration config = getBaseContext().getResources().getConfiguration();
        if ((config.screenLayout
                & Configuration.SCREENLAYOUT_SIZE_MASK)
                >= Configuration.SCREENLAYOUT_SIZE_LARGE) {
            mIsLargeScreen = true;

            if (getFragmentManager().findFragmentByTag(TAG_NOTE_EDITOR) != null) {
                mNoteEditorFragment = (NoteEditorFragment) getFragmentManager().findFragmentByTag(TAG_NOTE_EDITOR);
            } else if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mIsLandscape = true;
                addEditorFragment();
            }
        }

        mActionBar = getActionBar();
        mActionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
        mActionBar.setDisplayShowTitleEnabled(false);

        mUndoBarController = new UndoBarController(findViewById(R.id.undobar), this);

        getFragmentManager().addOnBackStackChangedListener(this);

        if (PrefUtils.getBoolPref(this, PrefUtils.PREF_FIRST_LAUNCH, true)) {
            // Create the welcome note
            Note welcomeNote = mNotesBucket.newObject("welcome-android");
            welcomeNote.setCreationDate(Calendar.getInstance());
            welcomeNote.setModificationDate(welcomeNote.getCreationDate());
            welcomeNote.setContent(getString(R.string.welcome_note));
            welcomeNote.getTitle();
            welcomeNote.save();

            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(PrefUtils.PREF_FIRST_LAUNCH, false);
            editor.commit();
        }

            if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            // Check share action
            Intent intent = getIntent();
            String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && !text.equals("")) {
                if (subject != null && !subject.equals("")) {
                    text = subject + "\n\n" + text;
                }
                Note note = mNotesBucket.newObject();
                note.setCreationDate(Calendar.getInstance());
                note.setModificationDate(note.getCreationDate());
                note.setContent(text);
                note.save();
                onNoteSelected(note.getSimperiumKey(), true);
                mTracker.sendEvent("note", "create_note", "external_share", null);
            }
        }
        currentApp.getSimperium().setOnUserCreatedListener(this);
        currentApp.getSimperium().setAuthenticationListener(this);
        checkForUpdates();
    }

    private void addEditorFragment() {
        FragmentManager fm = getFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        mNoteEditorFragment = new NoteEditorFragment();
        ft.add(R.id.noteFragmentContainer, mNoteEditorFragment, TAG_NOTE_EDITOR);
        ft.commitAllowingStateLoss();
        fm.executePendingTransactions();
    }

    @Override
    protected void onResume() {
        super.onResume();

        mNotesBucket.start();
        mTagsBucket.start();

        mNotesBucket.addOnNetworkChangeListener(this);
        mNotesBucket.addOnSaveObjectListener(this);
        mNotesBucket.addOnDeleteObjectListener(this);

        configureActionBar();
        invalidateOptionsMenu();
        checkForCrashes();
    }

    /**
     * Checks for a previously valid user that is now not authenticated
     * @return true if user has invalid authorization
     */
    private boolean userAuthenticationIsInvalid() {
        Simplenote currentApp = (Simplenote) getApplication();
        User currentUser = currentApp.getSimperium().getUser();
        if (currentUser != null && currentUser.getAuthenticationStatus() != User.AuthenticationStatus.UNKNOWN && currentUser.getAuthenticationStatus() == User.AuthenticationStatus.NOT_AUTHENTICATED) {
            return true;
        }
        return false;
    }

    private void checkForCrashes() {
        CrashManager.register(this, Config.hockey_app_id);
    }

    private void checkForUpdates() {
        // Remove this for store builds!
        UpdateManager.register(this, Config.hockey_app_id);
    }

    @Override
    protected void onPause() {
        super.onPause();

        mNotesBucket.stop();
        mTagsBucket.stop();

        mNotesBucket.removeOnNetworkChangeListener(this);
        mNotesBucket.removeOnSaveObjectListener(this);
        mNotesBucket.removeOnDeleteObjectListener(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EasyTracker.getInstance().activityStop(this);
    }

    public void setCurrentNote(Note note) {
        mCurrentNote = note;
    }

    // received a change from the network, refresh the list and the editor
    @Override
    public void onChange(Bucket<Note> bucket, Bucket.ChangeType type, String key) {
        final boolean resetEditor = mCurrentNote != null && mCurrentNote.getSimperiumKey().equals(key);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mNoteListFragment.refreshList();
                if (!resetEditor) return;
                if (mNoteEditorFragment != null) {
                    mNoteEditorFragment.refreshContent(true);
                }
            }
        });
    }

    @Override
    public void onSaveObject(Bucket<Note> bucket, Note object) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mNoteListFragment.refreshList();
            }
        });
    }

    @Override
    public void onDeleteObject(Bucket<Note> bucket, Note object) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mNoteListFragment.refreshList();
            }
        });
    }

    public boolean isLargeScreen() {
        return mIsLargeScreen;
    }

    public boolean isLargeScreenLandscape() {
        return mIsLargeScreen && mIsLandscape;
    }

    // nbradbury 01-Apr-2013
    private NoteListFragment getNoteListFragment() {
        return mNoteListFragment;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.notes_list, menu);

        mSearchMenuItem = menu.findItem(R.id.menu_search);
        mSearchView = (SearchView) mSearchMenuItem.getActionView();

        // Set a custom search view drawable
        int searchPlateId = mSearchView.getContext().getResources().getIdentifier("android:id/search_plate", null, null);
        View searchPlate = mSearchView.findViewById(searchPlateId);
        if (searchPlate != null)
            searchPlate.setBackgroundResource(R.drawable.search_view_selector);

        mSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextChange(String newText) {
                if (mSearchMenuItem.isActionViewExpanded())
                    getNoteListFragment().searchNotes(newText);
                return true;
            }

            @Override
            public boolean onQueryTextSubmit(String queryText) {
                getNoteListFragment().searchNotes(queryText);
                return true;
            }

        });
        mSearchMenuItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem menuItem) {
                showDetailPlaceholder();
                getNoteListFragment().setEmptyListMessage(getString(R.string.no_notes_found));
                getNoteListFragment().hideWelcomeView();
                mTracker.sendEvent("note", "searched_notes", "action_bar_search_tap", null);
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem menuItem) {
                // Show all notes again
                getNoteListFragment().setEmptyListMessage(getString(R.string.no_notes));
                getNoteListFragment().clearSearch();
                getNoteListFragment().setWelcomeViewVisibility();
                return true;
            }
        });

        Simplenote currentApp = (Simplenote) getApplication();
        if (currentApp.getSimperium().getUser() == null || currentApp.getSimperium().getUser().needsAuthentication())
            menu.findItem(R.id.menu_sign_in).setVisible(true);
        else
            menu.findItem(R.id.menu_sign_in).setVisible(false);

        FragmentManager fm = getFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            mActionBar.setDisplayHomeAsUpEnabled(true);
            menu.findItem(R.id.menu_create_note).setVisible(false);
            menu.findItem(R.id.menu_search).setVisible(false);
            menu.findItem(R.id.menu_preferences).setVisible(false);
            menu.findItem(R.id.menu_share).setVisible(true);
            menu.findItem(R.id.menu_delete).setVisible(true);
            if (mCurrentNote != null && mCurrentNote.isDeleted())
                menu.findItem(R.id.menu_delete).setTitle(R.string.undelete);
            menu.findItem(R.id.menu_edit_tags).setVisible(false);
            menu.findItem(R.id.menu_empty_trash).setVisible(false);
        } else if (isLargeScreenLandscape()) {
            mActionBar.setDisplayHomeAsUpEnabled(false);
            mActionBar.setHomeButtonEnabled(false);
            menu.findItem(R.id.menu_create_note).setVisible(true);
            menu.findItem(R.id.menu_search).setVisible(true);
            menu.findItem(R.id.menu_preferences).setVisible(true);
            if (mCurrentNote != null) {
                menu.findItem(R.id.menu_share).setVisible(true);
                menu.findItem(R.id.menu_delete).setVisible(true);
            } else {
                menu.findItem(R.id.menu_share).setVisible(false);
                menu.findItem(R.id.menu_delete).setVisible(false);
            }
            menu.findItem(R.id.menu_edit_tags).setVisible(true);
            menu.findItem(R.id.menu_empty_trash).setVisible(false);
        } else {
            mActionBar.setDisplayHomeAsUpEnabled(false);
            mActionBar.setHomeButtonEnabled(false);
            menu.findItem(R.id.menu_create_note).setVisible(true);
            menu.findItem(R.id.menu_search).setVisible(true);
            menu.findItem(R.id.menu_preferences).setVisible(true);
            menu.findItem(R.id.menu_share).setVisible(false);
            menu.findItem(R.id.menu_delete).setVisible(false);
            menu.findItem(R.id.menu_edit_tags).setVisible(true);
            menu.findItem(R.id.menu_empty_trash).setVisible(false);
        }

        // Are we looking at the trash? Adjust menu accordingly.
        if (mActionBar.getSelectedNavigationIndex() == TRASH_SELECTED_ID) {
            MenuItem trashItem = menu.findItem(R.id.menu_empty_trash);
            trashItem.setVisible(true);

            // Disable the trash icon if there are no notes trashed.
            Simplenote application = (Simplenote) getApplication();
            Bucket<Note> noteBucket = application.getNotesBucket();
            Query<Note> query = Note.allDeleted(noteBucket);
            if (query.count() == 0) {
                trashItem.setIcon(R.drawable.ab_icon_empty_trash_disabled);
                trashItem.setEnabled(false);
            } else {
                trashItem.setIcon(R.drawable.ab_icon_empty_trash);
                trashItem.setEnabled(true);
            }

            menu.findItem(R.id.menu_create_note).setVisible(false);
            menu.findItem(R.id.menu_search).setVisible(false);
            menu.findItem(R.id.menu_share).setVisible(false);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_preferences:
                // nbradbury - use startActivityForResult so onActivityResult can detect when user returns from preferences
                Intent i = new Intent(this, PreferencesActivity.class);
                startActivityForResult(i, Simplenote.INTENT_PREFERENCES);
                return true;
            case R.id.menu_sign_in:
                startLoginActivity(true);
                return true;
            case R.id.menu_edit_tags:
                Intent editTagsIntent = new Intent(this, TagsListActivity.class);
                startActivity(editTagsIntent);
                return true;
            case R.id.menu_create_note:
                getNoteListFragment().addNote();
                mTracker.sendEvent("note", "create_note", "action_bar_button", null);
                return true;
            case R.id.menu_share:
                if (mCurrentNote != null) {
                    Intent shareIntent = new Intent(android.content.Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, mCurrentNote.getContent());
                    startActivity(Intent.createChooser(shareIntent, getResources().getString(R.string.share_note)));
                    mTracker.sendEvent("note", "shared_note", "action_bar_share_button", null);
                }
                return true;
            case R.id.menu_delete:
                if (mNoteEditorFragment != null) {
                    if (mCurrentNote != null) {
                        mCurrentNote.setDeleted(!mCurrentNote.isDeleted());
                        mCurrentNote.setModificationDate(Calendar.getInstance());
                        mCurrentNote.save();

                        if (mCurrentNote.isDeleted()) {
                            mUndoBarController.setDeletedNote(mCurrentNote);
                            mUndoBarController.showUndoBar(false, getString(R.string.note_deleted), null);
                            mTracker.sendEvent("note", "deleted_note", "overflow_menu", null);
                        } else {
                            mTracker.sendEvent("note", "restored_note", "overflow_menu", null);
                        }
                        showDetailPlaceholder();
                    }
                    popNoteDetail();
                    NoteListFragment fragment = getNoteListFragment();
                    if (fragment != null) {
                        fragment.getPrefs();
                        fragment.refreshList();
                    }
                }
                return true;
            case R.id.menu_empty_trash:
                AlertDialog.Builder alert = new AlertDialog.Builder(this);

                alert.setTitle(R.string.empty_trash);
                alert.setMessage(R.string.confirm_empty_trash);
                alert.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        new emptyTrashTask().execute();
                        mTracker.sendEvent("note", "trash_emptied", "overflow_menu", null);
                    }
                });
                alert.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        // Do nothing, just closing the dialog
                    }
                });
                alert.show();
                return true;
            case android.R.id.home:
                popNoteDetail();
                invalidateOptionsMenu();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    protected void popNoteDetail() {
        FragmentManager fm = getFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            try {
                fm.popBackStack();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Callback method from {@link NoteListFragment.Callbacks} indicating that
     * the item with the given ID was selected.
     */
    @Override
    public void onNoteSelected(String noteID, boolean isNew) {

        if (mSearchMenuItem != null)
            mSearchMenuItem.collapseActionView();

        FragmentManager fm = getFragmentManager();

        if (!isLargeScreenLandscape()) {
            mActionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
            mActionBar.setDisplayShowTitleEnabled(true);

            // Create editor fragment
            Bundle arguments = new Bundle();
            arguments.putString(NoteEditorFragment.ARG_ITEM_ID, noteID);
            arguments.putBoolean(NoteEditorFragment.ARG_NEW_NOTE, isNew);
            mNoteEditorFragment = new NoteEditorFragment();
            mNoteEditorFragment.setArguments(arguments);

            // Add editor fragment to stack
            FragmentTransaction ft = fm.beginTransaction();
            ft.setCustomAnimations(R.animator.fade_in, R.animator.fade_out, R.animator.fade_in, R.animator.fade_out);
            ft.replace(R.id.noteFragmentContainer, mNoteEditorFragment);
            ft.addToBackStack(null);
            ft.commitAllowingStateLoss();
            fm.executePendingTransactions();
            invalidateOptionsMenu();
        } else {
            mNoteEditorFragment.setIsNewNote(isNew);
            mNoteEditorFragment.setNote(noteID);
            getNoteListFragment().setNoteSelected(noteID);
        }

        mTracker.sendEvent("note", "viewed_note", "note_list_row_tap", null);
    }

    @Override
    public boolean onNavigationItemSelected(int itemPosition, long itemId) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void onUserCreated(User user) {
        // New account created
        mTracker.sendEvent("user", "new_account_created", "account_created_from_login_activity", null);
    }

    public void onAuthenticationStatusChange(User.AuthenticationStatus status) {
        if (status == User.AuthenticationStatus.AUTHENTICATED) {
            // User signed in
            mTracker.sendEvent("user", "signed_in", "signed_in_from_login_activity", null);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    invalidateOptionsMenu();
                }
            });
        }
        // TODO Fix #91
        /* else if (status == User.AuthenticationStatus.NOT_AUTHENTICATED) {
            startLoginActivity(true);
        }*/
    }

    public void startLoginActivity(boolean signInFirst) {
        Intent loginIntent = new Intent(this, LoginActivity.class);
        if (signInFirst)
            loginIntent.putExtra(LoginActivity.EXTRA_SIGN_IN_FIRST, true);
        startActivityForResult(loginIntent, Simperium.SIGNUP_SIGNIN_REQUEST);
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case Simplenote.INTENT_PREFERENCES:
                // nbradbury - refresh note list when user returns from preferences (in case they changed anything)
                NoteListFragment fragment = getNoteListFragment();
                if (fragment != null) {
                    fragment.getPrefs();
                    fragment.refreshList();
                }
                break;
            /*case Simperium.SIGNUP_SIGNIN_REQUEST:
                if (resultCode == Activity.RESULT_CANCELED && userAuthenticationIsInvalid()) {
                    finish();
                }
                break;*/
        }
    }

    @Override
    public void onUndo(Parcelable p) {
        if (mUndoBarController != null && mUndoBarController.getDeletedNote() != null) {
            Note deletedNote = mUndoBarController.getDeletedNote();
            if (deletedNote != null) {
                deletedNote.setDeleted(false);
                deletedNote.setModificationDate(Calendar.getInstance());
                deletedNote.save();
                NoteListFragment fragment = getNoteListFragment();
                if (fragment != null) {
                    fragment.getPrefs();
                    fragment.refreshList();
                }
            }
        }
    }

    @Override
    public void onBackStackChanged() {
        configureActionBar();
        invalidateOptionsMenu();
    }

    private void configureActionBar() {
        if (getFragmentManager().getBackStackEntryCount() > 0) {
            ActionBar ab = mActionBar;
            if (ab != null) {
                ab.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
            }
        } else {
            ActionBar ab = mActionBar;
            if (ab != null) {
                ab.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
                ab.setDisplayShowTitleEnabled(false);
                ab.setTitle("");
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mIsLargeScreen) {
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                // Add the editor fragment
                mIsLandscape = true;
                popNoteDetail();
                addEditorFragment();
                if (mNoteListFragment != null) {
                    mNoteListFragment.setActivateOnItemClick(true);
                    mNoteListFragment.setDividerVisible(true);
                }
                // Select the current note on a tablet
                if (mCurrentNote != null)
                    onNoteSelected(mCurrentNote.getSimperiumKey(), false);
                else {
                    mNoteEditorFragment.setPlaceholderVisible(true);
                    mNoteListFragment.getListView().clearChoices();
                }
                invalidateOptionsMenu();
            } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT && mNoteEditorFragment != null) {
                // Remove the editor fragment when rotating back to portrait
                mIsLandscape = false;
                mCurrentNote = null;
                if (mNoteListFragment != null) {
                    mNoteListFragment.setActivateOnItemClick(false);
                    mNoteListFragment.setDividerVisible(false);
                    mNoteListFragment.setActivatedPosition(ListView.INVALID_POSITION);
                    mNoteListFragment.refreshList();
                }
                FragmentManager fm = getFragmentManager();
                FragmentTransaction ft = fm.beginTransaction();
                ft.remove(mNoteEditorFragment);
                mNoteEditorFragment = null;
                ft.commitAllowingStateLoss();
                fm.executePendingTransactions();
                invalidateOptionsMenu();
            }
        }
    }

    public void showDetailPlaceholder() {
        if (isLargeScreenLandscape() && mNoteEditorFragment != null) {
            mCurrentNote = null;
            invalidateOptionsMenu();
            mNoteEditorFragment.setPlaceholderVisible(true);
        }
    }

    private class emptyTrashTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... voids) {
            Simplenote application = (Simplenote) getApplication();
            Bucket<Note> noteBucket = application.getNotesBucket();
            Query<Note> query = Note.allDeleted(noteBucket);
            Bucket.ObjectCursor c = query.execute();
            while (c.moveToNext()) {
                c.getObject().delete();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void nada) {
            invalidateOptionsMenu();
            showDetailPlaceholder();
        }
    }

}