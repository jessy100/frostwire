/*
 * Created by Angel Leon (@gubatron), Alden Torres (aldenml)
 * Copyright (c) 2011-2017, FrostWire(R). All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.frostwire.bittorrent;

import com.frostwire.jlibtorrent.AlertListener;
import com.frostwire.jlibtorrent.Entry;
import com.frostwire.jlibtorrent.Priority;
import com.frostwire.jlibtorrent.SessionManager;
import com.frostwire.jlibtorrent.SessionParams;
import com.frostwire.jlibtorrent.SettingsPack;
import com.frostwire.jlibtorrent.TcpEndpoint;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.Vectors;
import com.frostwire.jlibtorrent.alerts.Alert;
import com.frostwire.jlibtorrent.alerts.AlertType;
import com.frostwire.jlibtorrent.alerts.ExternalIpAlert;
import com.frostwire.jlibtorrent.alerts.FastresumeRejectedAlert;
import com.frostwire.jlibtorrent.alerts.ListenFailedAlert;
import com.frostwire.jlibtorrent.alerts.ListenSucceededAlert;
import com.frostwire.jlibtorrent.alerts.TorrentAlert;
import com.frostwire.jlibtorrent.swig.bdecode_node;
import com.frostwire.jlibtorrent.swig.byte_vector;
import com.frostwire.jlibtorrent.swig.entry;
import com.frostwire.jlibtorrent.swig.error_code;
import com.frostwire.jlibtorrent.swig.libtorrent;
import com.frostwire.jlibtorrent.swig.session_params;
import com.frostwire.jlibtorrent.swig.settings_pack;
import com.frostwire.platform.FileSystem;
import com.frostwire.platform.Platforms;
import com.frostwire.search.torrent.TorrentCrawledSearchResult;
import com.frostwire.util.Logger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.FilenameFilter;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static com.frostwire.jlibtorrent.alerts.AlertType.ADD_TORRENT;
import static com.frostwire.jlibtorrent.alerts.AlertType.EXTERNAL_IP;
import static com.frostwire.jlibtorrent.alerts.AlertType.FASTRESUME_REJECTED;
import static com.frostwire.jlibtorrent.alerts.AlertType.LISTEN_FAILED;
import static com.frostwire.jlibtorrent.alerts.AlertType.LISTEN_SUCCEEDED;
import static com.frostwire.jlibtorrent.alerts.AlertType.PEER_LOG;
import static com.frostwire.jlibtorrent.alerts.AlertType.TORRENT_LOG;

/**
 * @author gubatron
 * @author aldenml
 */
public final class BTEngine extends SessionManager {

    private static final Logger LOG = Logger.getLogger(BTEngine.class);

    private static final String STATE_VERSION_KEY = "state_version";
    // this constant only changes when the libtorrent settings_pack ABI is
    // incompatible with the previous version, it should only happen from
    // time to time, not in every version
    private static final String STATE_VERSION_VALUE = "1.2.0.6";
    public static BTContext ctx;


    private final InnerListener innerListener;
    private final Queue<RestoreDownloadTask> restoreDownloadsQueue;

    private BTSettingsManager BTSettingsManager = new BTSettingsManager(STATE_VERSION_KEY, STATE_VERSION_VALUE);
    private BTFileHandler FileHandler = new BTFileHandler();

    private BTEngine() {
        super(false);
        this.innerListener = new InnerListener(this);
        this.restoreDownloadsQueue = new LinkedList<>();
    }

    private static class Loader {
        static final BTEngine INSTANCE = new BTEngine();
    }

    public static BTEngine getInstance() {
        if (ctx == null) {
            throw new IllegalStateException("Context can't be null");
        }
        return Loader.INSTANCE;
    }

    public BTEngineListener getListener() {
        return innerListener.getListener();
    }

    public void setListener(BTEngineListener listener) {
        innerListener.setListener(listener);
    }

    @Override
    public void start() {
        SessionParams params = BTSettingsManager.loadSettings();

        settings_pack sp = params.settings().swig();
        sp.set_str(settings_pack.string_types.listen_interfaces.swigValue(), ctx.interfaces);
        sp.set_int(settings_pack.int_types.max_retry_port_bind.swigValue(), ctx.retries);
        sp.set_str(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), dhtBootstrapNodes());
        sp.set_int(settings_pack.int_types.active_limit.swigValue(), 2000);
        sp.set_int(settings_pack.int_types.stop_tracker_timeout.swigValue(), 0);
        sp.set_int(settings_pack.int_types.alert_queue_size.swigValue(), 5000);

        super.start(params);
    }

    @Override
    protected void onBeforeStart() {
        addListener(innerListener);
    }

    @Override
    protected void onBeforeStop() {
        removeListener(innerListener);
        BTSettingsManager.saveSettings();
    }

    @Override
    public void moveStorage(File dataDir) {
        if (swig() == null) {
            return;
        }

        ctx.dataDir = dataDir; // this will be removed when we start using platform
        super.moveStorage(dataDir);
    }

    @Override
    protected void onApplySettings(SettingsPack sp) {
        BTSettingsManager.saveSettings();
    }

    @Override
    public byte[] saveState() {
        if (swig() == null) {
            return null;
        }

        entry e = new entry();
        swig().save_state(e);
        e.set(STATE_VERSION_KEY, STATE_VERSION_VALUE);
        return Vectors.byte_vector2bytes(e.bencode());
    }

    public void download(File torrent, File saveDir, boolean[] selection) {
        if (swig() == null) {
            return;
        }

        saveDir = setupSaveDir(saveDir);
        if (saveDir == null) {
            return;
        }

        TorrentInfo ti = new TorrentInfo(torrent);

        Priority[] priorities = null;

        TorrentHandle th = find(ti.infoHash());
        boolean exists = th != null;

        if (selection != null) {
            if (th != null) {
                priorities = th.filePriorities();
            } else {
                priorities = Priority.array(Priority.IGNORE, ti.numFiles());
            }

            for (int i = 0; i < selection.length; i++) {
                if (selection[i]) {
                    priorities[i] = Priority.NORMAL;
                }
            }
        }

        download(ti, saveDir, priorities, null, null);

        if (!exists) {
            FileHandler.saveResumeTorrent(ti);
        }
    }

    public void download(TorrentInfo ti, File saveDir, boolean[] selection, List<TcpEndpoint> peers) {
        download(ti, saveDir, selection, peers, false);
    }

    public void download(TorrentInfo ti, File saveDir, boolean[] selection, List<TcpEndpoint> peers, boolean dontSaveTorrentFile) {
        if (swig() == null) {
            return;
        }

        saveDir = setupSaveDir(saveDir);
        if (saveDir == null) {
            return;
        }

        Priority[] priorities = null;

        TorrentHandle th = find(ti.infoHash());
        boolean torrentHandleExists = th != null;

        if (selection != null) {
            if (torrentHandleExists) {
                priorities = th.filePriorities();
            } else {
                priorities = Priority.array(Priority.IGNORE, ti.numFiles());
            }

            if (priorities != null) {
                for (int i = 0; i < selection.length; i++) {
                    if (selection[i] && i < priorities.length) {
                        priorities[i] = Priority.NORMAL;
                    }
                }
            }
        }

        download(ti, saveDir, priorities, null, peers);

        if (!torrentHandleExists) {
            FileHandler.saveResumeTorrent(ti);
            if (!dontSaveTorrentFile) {
                FileHandler.saveTorrent(ti);
            }
        }
    }

    public void download(TorrentCrawledSearchResult sr, File saveDir) {
        download(sr, saveDir, false);
    }

    public void download(TorrentCrawledSearchResult sr, File saveDir, boolean dontSaveTorrentFile) {
        if (swig() == null) {
            return;
        }

        saveDir = setupSaveDir(saveDir);
        if (saveDir == null) {
            return;
        }

        TorrentInfo ti = sr.getTorrentInfo();
        int fileIndex = sr.getFileIndex();

        TorrentHandle th = find(ti.infoHash());
        boolean exists = th != null;

        if (th != null) {
            Priority[] priorities = th.filePriorities();
            if (priorities[fileIndex] == Priority.IGNORE) {
                priorities[fileIndex] = Priority.NORMAL;
                download(ti, saveDir, priorities, null, null);
            }
        } else {
            Priority[] priorities = Priority.array(Priority.IGNORE, ti.numFiles());
            priorities[fileIndex] = Priority.NORMAL;
            download(ti, saveDir, priorities, null, null);
        }

        if (!exists) {
            FileHandler.saveResumeTorrent(ti);
            if (!dontSaveTorrentFile) {
                FileHandler.saveTorrent(ti);
            }
        }
    }

    public void restoreDownloads() {
        if (swig() == null) {
            return;
        }

        if (ctx.homeDir == null || !ctx.homeDir.exists()) {
            LOG.warn("Wrong setup with BTEngine home dir");
            return;
        }

        File[] torrents = ctx.homeDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name != null && FilenameUtils.getExtension(name).toLowerCase().equals("torrent");
            }
        });

        if (torrents != null) {
            for (File t : torrents) {
                try {
                    String infoHash = FilenameUtils.getBaseName(t.getName());
                    if (infoHash != null) {
                        File resumeFile = FileHandler.resumeDataFile(infoHash);

                        File savePath = FileHandler.readSavePath(infoHash);
                        if (setupSaveDir(savePath) == null) {
                            LOG.warn("Can't create data dir or mount point is not accessible");
                            return;
                        }

                        restoreDownloadsQueue.add(new RestoreDownloadTask(t, null, null, resumeFile));
                    }
                } catch (Throwable e) {
                    LOG.error("Error restoring torrent download: " + t, e);
                }
            }
        }

        migrateVuzeDownloads();

        runNextRestoreDownloadTask();
    }













    private void migrateVuzeDownloads() {
        try {
            File dir = new File(ctx.homeDir.getParent(), "azureus");
            File file = new File(dir, "downloads.config");

            if (file.exists()) {
                Entry configEntry = Entry.bdecode(file);
                List<Entry> downloads = configEntry.dictionary().get("downloads").list();

                for (Entry d : downloads) {
                    try {
                        ParseVuzeDownload(d);
                    } catch (Throwable e) {
                        LOG.error("Error restoring vuze torrent download", e);
                    }
                }

                file.delete();
            }
        } catch (Throwable e) {
            LOG.error("Error migrating old vuze downloads", e);
        }
    }

    private void ParseVuzeDownload(Entry d)
    {
        Map<String, Entry> map = d.dictionary();
        File saveDir = new File(map.get("save_dir").string());
        File torrent = new File(map.get("torrent").string());
        List<Entry> filePriorities = map.get("file_priorities").list();

        Priority[] priorities = Priority.array(Priority.IGNORE, filePriorities.size());
        for (int i = 0; i < filePriorities.size(); i++) {
            long p = filePriorities.get(i).integer();
            if (p != 0) {
                priorities[i] = Priority.NORMAL;
            }
        }

        if (torrent.exists() && saveDir.exists()) {
            LOG.info("Restored old vuze download: " + torrent);
            restoreDownloadsQueue.add(new RestoreDownloadTask(torrent, saveDir, priorities, null));
            FileHandler.saveResumeTorrent(new TorrentInfo(torrent));
        }
    }

    private File setupSaveDir(File saveDir) {
        File result = null;

        if (saveDir == null) {
            if (ctx.dataDir != null) {
                result = ctx.dataDir;
            } else {
                LOG.warn("Unable to setup save dir path, review your logic, both saveDir and ctx.dataDir are null.");
            }
        } else {
            result = saveDir;
        }

        FileSystem fs = Platforms.get().fileSystem();

        if (result != null && !fs.isDirectory(result) && !fs.mkdirs(result)) {
            result = null;
            LOG.warn("Failed to create save dir to download");
        }

        if (result != null && !fs.canWrite(result)) {
            result = null;
            LOG.warn("Failed to setup save dir with write access");
        }

        return result;
    }

    public void runNextRestoreDownloadTask() {
        RestoreDownloadTask task = null;
        try {
            if (!restoreDownloadsQueue.isEmpty()) {
                task = restoreDownloadsQueue.poll();
            }
        } catch (Throwable t) {
            // on Android, LinkedList's .poll() implementation throws a NoSuchElementException
        }
        if (task != null) {
            task.run();
        }
    }

    private void download(TorrentInfo ti, File saveDir, Priority[] priorities, File resumeFile, List<TcpEndpoint> peers) {

        TorrentHandle th = find(ti.infoHash());

        if (th != null) {
            // found a download with the same hash, just adjust the priorities if needed
            if (priorities != null) {
                if (ti.numFiles() != priorities.length) {
                    throw new IllegalArgumentException("The priorities length should be equals to the number of files");
                }

                th.prioritizeFiles(priorities);
                innerListener.fireDownloadUpdate(th);
                th.resume();
            } else {
                // did they just add the entire torrent (therefore not selecting any priorities)
                final Priority[] wholeTorrentPriorities = Priority.array(Priority.NORMAL, ti.numFiles());
                th.prioritizeFiles(wholeTorrentPriorities);
                innerListener.fireDownloadUpdate(th);
                th.resume();
            }
        } else { // new download
            download(ti, saveDir, resumeFile, priorities, peers);
        }
    }

    private final class RestoreDownloadTask implements Runnable {

        private final File torrent;
        private final File saveDir;
        private final Priority[] priorities;
        private final File resume;

        public RestoreDownloadTask(File torrent, File saveDir, Priority[] priorities, File resume) {
            this.torrent = torrent;
            this.saveDir = saveDir;
            this.priorities = priorities;
            this.resume = resume;
        }

        @Override
        public void run() {
            try {
                download(new TorrentInfo(torrent), saveDir, resume, priorities, null);
            } catch (Throwable e) {
                LOG.error("Unable to restore download from previous session. (" + torrent.getAbsolutePath() + ")", e);
            }
        }
    }

    private static String dhtBootstrapNodes() {
        StringBuilder sb = new StringBuilder();

        sb.append("dht.libtorrent.org:25401").append(",");
        sb.append("router.bittorrent.com:6881").append(",");
        sb.append("dht.transmissionbt.com:6881").append(",");
        // for DHT IPv6
        sb.append("outer.silotis.us:6881");

        return sb.toString();
    }




}
