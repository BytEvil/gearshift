package org.sugr.gearshift;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.NavUtils;
import android.support.v4.content.LocalBroadcastManager;
import android.text.Html;
import android.text.Spanned;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.sugr.gearshift.datasource.DataSource;
import org.sugr.gearshift.service.DataService;
import org.sugr.gearshift.service.DataServiceManager;

import java.util.ArrayList;
import java.util.List;


/**
 * An activity representing a single Torrent detail screen. This
 * activity is only used on handset devices. On tablet-size devices,
 * item details are presented side-by-side with a list of items
 * in a {@link TorrentListActivity}.
 * <p>
 * This activity is mostly just a 'shell' activity containing nothing
 * more than a {@link TorrentDetailFragment}.
 */
public class TorrentDetailActivity extends BaseTorrentActivity {
    private int currentTorrentPosition = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent in = getIntent();

        profile = in.getParcelableExtra(G.ARG_PROFILE);
        profile.setContext(this);
        session = in.getParcelableExtra(G.ARG_SESSION);
        setSession(session);

        serviceReceiver = new ServiceReceiver();
        manager = new DataServiceManager(this, profile.getId())
            .setDetails(true).onRestoreInstanceState(savedInstanceState).startUpdating();

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_torrent_detail);

        // Show the Up button in the action bar.
        getActionBar().setDisplayHomeAsUpEnabled(true);

        currentTorrentPosition = in.getIntExtra(G.ARG_PAGE_POSITION, 0);
        if (currentTorrentPosition < 0) {
            currentTorrentPosition = 0;
        }
        if (savedInstanceState == null) {
            Bundle arguments = new Bundle();
            arguments.putInt(G.ARG_PAGE_POSITION, currentTorrentPosition);
            arguments.putBoolean(TorrentDetailFragment.ARG_SHOW_PAGER,
                    true);
            TorrentDetailFragment fragment = new TorrentDetailFragment();
            fragment.setArguments(arguments);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.torrent_detail_container, fragment, G.DETAIL_FRAGMENT_TAG)
                    .commit();
        }
        new SessionTask(this, SessionTask.Flags.START_TORRENT_TASK).execute();
    }

    @Override public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (manager != null) {
            manager.onSaveInstanceState(outState);
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        this.menu = menu;

        getMenuInflater().inflate(R.menu.torrent_detail_activity, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                NavUtils.navigateUpTo(this, new Intent(this, TorrentListActivity.class));
                return true;
            case R.id.menu_refresh:
                manager.update();
                setRefreshing(true, DataService.Requests.GET_TORRENTS);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onTrimMemory(int level) {
        switch (level) {
            case TRIM_MEMORY_RUNNING_LOW:
            case TRIM_MEMORY_RUNNING_CRITICAL:
            case TRIM_MEMORY_COMPLETE:
                break;
            default:
                return;
        }
        if (!isFinishing()) {
            finish();
            Toast.makeText(this, "Low memory", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onPageSelected(int position) {
        currentTorrentPosition = position;

        manager.setTorrentsToUpdate(getCurrentTorrentHashStrings());
    }

    private String[] getCurrentTorrentHashStrings() {
        TorrentDetailFragment fragment =
            (TorrentDetailFragment) getSupportFragmentManager().findFragmentByTag(G.DETAIL_FRAGMENT_TAG);

        if (fragment == null) {
            return null;
        }

        int current = currentTorrentPosition;
        int offscreen = 1;
        int count = offscreen * 2 + 1;
        if (current == 0) {
            count--;
        }

        List<String> hashList = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            int position = current + i - (current == 0 ? 0 : offscreen);
            String hash = fragment.getTorrentHashString(position);
            if (hash != null) {
                hashList.add(hash);
            }
        }

        return hashList.toArray(new String[hashList.size()]);
    }

    private class ServiceReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            int error = intent.getIntExtra(G.ARG_ERROR, 0);

            String type = intent.getStringExtra(G.ARG_REQUEST_TYPE);
            switch (type) {
                case DataService.Requests.GET_SESSION:
                case DataService.Requests.SET_SESSION:
                case DataService.Requests.GET_TORRENTS:
                case DataService.Requests.ADD_TORRENT:
                case DataService.Requests.REMOVE_TORRENT:
                case DataService.Requests.SET_TORRENT:
                case DataService.Requests.SET_TORRENT_ACTION:
                case DataService.Requests.SET_TORRENT_LOCATION:
                    setRefreshing(false, type);
                    if (error == 0) {
                        findViewById(R.id.fatal_error_layer).setVisibility(View.GONE);

                        int flags = TorrentTask.Flags.CONNECTED;
                        switch (type) {
                            case DataService.Requests.GET_SESSION:
                                new SessionTask(TorrentDetailActivity.this, 0).execute();
                                break;
                            case DataService.Requests.SET_SESSION:
                                manager.getSession();
                                break;
                            case DataService.Requests.GET_TORRENTS:
                                boolean added = intent.getBooleanExtra(G.ARG_ADDED, false);
                                boolean removed = intent.getBooleanExtra(G.ARG_REMOVED, false);
                                boolean statusChanged = intent.getBooleanExtra(G.ARG_STATUS_CHANGED, false);
                                boolean incomplete = intent.getBooleanExtra(G.ARG_INCOMPLETE_METADATA, false);

                                if (added) {
                                    flags |= TorrentTask.Flags.HAS_ADDED;
                                }
                                if (removed) {
                                    flags |= TorrentTask.Flags.HAS_REMOVED;
                                }
                                if (statusChanged) {
                                    flags |= TorrentTask.Flags.HAS_STATUS_CHANGED;
                                }
                                if (incomplete) {
                                    flags |= TorrentTask.Flags.HAS_INCOMPLETE_METADATA;
                                }

                                new TorrentTask(TorrentDetailActivity.this, flags).execute();
                                break;
                            case DataService.Requests.ADD_TORRENT:
                                manager.update();
                                flags |= TorrentTask.Flags.HAS_ADDED | TorrentTask.Flags.HAS_INCOMPLETE_METADATA;
                                new TorrentTask(TorrentDetailActivity.this, flags).execute();
                                break;
                            case DataService.Requests.REMOVE_TORRENT:
                                manager.update();
                                flags |= TorrentTask.Flags.HAS_REMOVED;
                                new TorrentTask(TorrentDetailActivity.this, flags).execute();
                                break;
                            case DataService.Requests.SET_TORRENT_LOCATION:
                                manager.update();
                                flags |= TorrentTask.Flags.HAS_ADDED | TorrentTask.Flags.HAS_REMOVED;
                                new TorrentTask(TorrentDetailActivity.this, flags).execute();
                                break;
                            case DataService.Requests.SET_TORRENT:
                            case DataService.Requests.SET_TORRENT_ACTION:
                                manager.update();
                                flags |= TorrentTask.Flags.HAS_STATUS_CHANGED;
                                new TorrentTask(TorrentDetailActivity.this, flags).execute();
                                break;
                        }
                    } else {
                        if (error == TransmissionData.Errors.DUPLICATE_TORRENT) {
                            Toast.makeText(TorrentDetailActivity.this,
                                R.string.duplicate_torrent, Toast.LENGTH_SHORT).show();
                        } else if (error == TransmissionData.Errors.INVALID_TORRENT) {
                            Toast.makeText(TorrentDetailActivity.this,
                                R.string.invalid_torrent, Toast.LENGTH_SHORT).show();
                        } else {
                            findViewById(R.id.fatal_error_layer).setVisibility(View.VISIBLE);
                            TextView text = (TextView) findViewById(R.id.transmission_error);
                            FragmentManager manager = getSupportFragmentManager();
                            TorrentDetailFragment fragment =
                                (TorrentDetailFragment) manager.findFragmentByTag(G.DETAIL_FRAGMENT_TAG);
                            if (fragment != null) {
                                fragment.notifyTorrentListChanged(null, error, false, false, false, false, false);
                            }

                            if (error == TransmissionData.Errors.NO_CONNECTIVITY) {
                                text.setText(Html.fromHtml(getString(R.string.no_connectivity_empty_list)));
                            } else if (error == TransmissionData.Errors.ACCESS_DENIED) {
                                text.setText(Html.fromHtml(getString(R.string.access_denied_empty_list)));
                            } else if (error == TransmissionData.Errors.NO_JSON) {
                                text.setText(Html.fromHtml(getString(R.string.no_json_empty_list)));
                            } else if (error == TransmissionData.Errors.NO_CONNECTION) {
                                text.setText(Html.fromHtml(getString(R.string.no_connection_empty_list)));
                            } else if (error == TransmissionData.Errors.THREAD_ERROR) {
                                text.setText(Html.fromHtml(getString(R.string.thread_error_empty_list)));
                            } else if (error == TransmissionData.Errors.RESPONSE_ERROR) {
                                text.setText(Html.fromHtml(getString(R.string.response_error_empty_list)));
                            } else if (error == TransmissionData.Errors.TIMEOUT) {
                                text.setText(Html.fromHtml(getString(R.string.timeout_empty_list)));
                            } else if (error == TransmissionData.Errors.OUT_OF_MEMORY) {
                                text.setText(Html.fromHtml(getString(R.string.out_of_memory_empty_list)));
                            } else if (error == TransmissionData.Errors.JSON_PARSE_ERROR) {
                                text.setText(Html.fromHtml(getString(R.string.json_parse_empty_list)));
                            }
                        }
                    }
                    break;
            }
        }
    }

    private class SessionTask extends AsyncTask<Void, Void, TransmissionSession> {
        DataSource readSource;
        boolean startTorrentTask;

        public class Flags {
            public static final int START_TORRENT_TASK = 1;
        }

        public SessionTask(Context context, int flags) {
            super();

            readSource = new DataSource(context);
            if ((flags & Flags.START_TORRENT_TASK) == Flags.START_TORRENT_TASK) {
                startTorrentTask = true;
            }
        }

        @Override protected TransmissionSession doInBackground(Void... ignored) {
            try {
                readSource.open();

                TransmissionSession session = readSource.getSession();
                session.setDownloadDirectories(profile, readSource.getDownloadDirectories());

                return session;
            } finally {
                if (readSource.isOpen()) {
                    readSource.close();
                }
            }
        }

        @Override protected void onPostExecute(TransmissionSession session) {
            setSession(session);

            if (startTorrentTask) {
                new TorrentTask(TorrentDetailActivity.this, 0).execute();
            }
        }
    }

    private class TorrentTask extends AsyncTask<Void, Void, Cursor> {
        DataSource readSource;
        boolean added, removed, statusChanged, incompleteMetadata, update, connected;

        public class Flags {
            public static final int HAS_ADDED = 1;
            public static final int HAS_REMOVED = 1 << 1;
            public static final int HAS_STATUS_CHANGED = 1 << 2;
            public static final int HAS_INCOMPLETE_METADATA = 1 << 3;
            public static final int UPDATE = 1 << 4;
            public static final int CONNECTED = 1 << 5;
        }

        public TorrentTask(Context context, int flags) {
            super();

            readSource = new DataSource(context);
            if ((flags & Flags.HAS_ADDED) == Flags.HAS_ADDED) {
                added = true;
            }
            if ((flags & Flags.HAS_REMOVED) == Flags.HAS_REMOVED) {
                removed = true;
            }
            if ((flags & Flags.HAS_STATUS_CHANGED) == Flags.HAS_STATUS_CHANGED) {
                statusChanged = true;
            }
            if ((flags & Flags.HAS_INCOMPLETE_METADATA) == Flags.HAS_INCOMPLETE_METADATA) {
                incompleteMetadata = true;
            }
            if ((flags & Flags.UPDATE) == Flags.UPDATE) {
                update = true;
            }
            if ((flags & Flags.CONNECTED) == Flags.CONNECTED) {
                connected = true;
            }
        }

        @Override protected Cursor doInBackground(Void... unused) {
            try {
                readSource.open();

                Cursor cursor = readSource.getTorrentCursor();

                /* Fill the cursor window */
                cursor.getCount();

                return cursor;
            } finally {
                if (readSource.isOpen()) {
                    readSource.close();
                }
            }
        }

        @Override protected void onPostExecute(Cursor cursor) {
            if (cursor.getCount() == 0) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(TorrentDetailActivity.this);

                Spanned text;
                if (prefs.getString(G.PREF_LIST_SEARCH, "").equals("")
                    && prefs.getString(G.PREF_LIST_DIRECTORY, "").equals("")
                    && prefs.getString(G.PREF_LIST_TRACKER, "").equals("")
                    && prefs.getString(G.PREF_LIST_FILTER, G.FilterBy.ALL.name()).equals(G.FilterBy.ALL.name())) {
                    text = Html.fromHtml(getString(R.string.no_torrents_empty_list));
                } else {
                    text = Html.fromHtml(getString(R.string.no_filtered_torrents_empty_list));
                }
                Toast.makeText(TorrentDetailActivity.this, text, Toast.LENGTH_SHORT).show();
            }

            FragmentManager manager = getSupportFragmentManager();
            TorrentDetailFragment detail = (TorrentDetailFragment) manager.findFragmentByTag(
                G.DETAIL_FRAGMENT_TAG);
            if (detail != null) {
                detail.notifyTorrentListChanged(cursor, 0, added, removed,
                    statusChanged, incompleteMetadata, connected);
            }

            if (update) {
                update = false;
                TorrentDetailActivity.this.manager.update();
            }
        }
    }
}