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

import com.frostwire.jlibtorrent.*;
import com.frostwire.jlibtorrent.swig.entry;
import com.frostwire.jlibtorrent.swig.settings_pack;
import com.frostwire.search.torrent.TorrentCrawledSearchResult;
import com.frostwire.util.Logger;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

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
    public static final BTContext ctx = new BTContext();


    private final InnerListener innerListener;
    private final Queue<RestoreDownloadTask> restoreDownloadsQueue;

    private BTSettingsManager btSettingsManager = new BTSettingsManager(STATE_VERSION_KEY, STATE_VERSION_VALUE);
    private BTFileHandler fileHandler = new BTFileHandler();

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
        SessionParams params = btSettingsManager.loadSettings();

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
        btSettingsManager.saveSettings();
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
        btSettingsManager.saveSettings();
    }

    @Override
    public byte[] saveState() {
        if (swig() == null) {
            return new byte[0];
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

        saveDir = fileHandler.setupSaveDir(saveDir);
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
            fileHandler.saveResumeTorrent(ti);
        }
    }

    public void download(TorrentInfo ti, File saveDir, boolean[] selection, List<TcpEndpoint> peers) {
        download(ti, saveDir, selection, peers, false);
    }

    public void download(TorrentInfo ti, File saveDir, boolean[] selection, List<TcpEndpoint> peers, boolean dontSaveTorrentFile) {
        if (swig() == null) {
            return;
        }

        saveDir = fileHandler.setupSaveDir(saveDir);
        if (saveDir == null) {
            return;
        }

        Priority[] priorities = null;

        TorrentHandle th = find(ti.infoHash());
        boolean torrentHandleExists = th != null;

        if (selection != null) {
            priorities = ParsePriorities(th, ti, selection);
        }

        download(ti, saveDir, priorities, null, peers);

        if (!torrentHandleExists) {
            fileHandler.saveResumeTorrent(ti);
            if (!dontSaveTorrentFile) {
                fileHandler.saveTorrent(ti);
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

        saveDir = fileHandler.setupSaveDir(saveDir);
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
            fileHandler.saveResumeTorrent(ti);
            if (!dontSaveTorrentFile) {
                fileHandler.saveTorrent(ti);
            }
        }
    }

    public Priority[] ParsePriorities(TorrentHandle torrentHandle, TorrentInfo torrentInfo, boolean[] selection) {
        Priority[] priorities;

        if (torrentHandle != null) {
            priorities = torrentHandle.filePriorities();
        } else {
            priorities = Priority.array(Priority.IGNORE, torrentInfo.numFiles());
        }

        if (priorities != null) {
            for (int i = 0; i < selection.length; i++) {
                if (selection[i] && i < priorities.length) {
                    priorities[i] = Priority.NORMAL;
                }
            }
        }

        return priorities;
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
                return name != null && FilenameUtils.getExtension(name).equalsIgnoreCase("torrent");
            }
        });

        if (torrents != null) {
            for (File t : torrents) {
                try {
                    createFileToResume(t);
                } catch (Exception e) {
                    LOG.error("Error restoring torrent download: " + t, e);
                }
            }
        }

        migrateVuzeDownloads();

        runNextRestoreDownloadTask();
    }

    private void createFileToResume(File t) {
        String infoHash = FilenameUtils.getBaseName(t.getName());
        if (infoHash != null) {
            File resumeFile = fileHandler.resumeDataFile(infoHash);

            File savePath = fileHandler.readSavePath(infoHash);
            if (fileHandler.setupSaveDir(savePath) == null) {
                LOG.warn("Can't create data dir or mount point is not accessible");
                return;
            }

            restoreDownloadsQueue.add(new RestoreDownloadTask(t, null, null, resumeFile));
        }
    }

    private void migrateVuzeDownloads() {
        try {
            ConvertFuzeFile();
        } catch (Exception e) {
            LOG.error("Error migrating old vuze downloads", e);
        }
    }

    private void parseVuzeDownload(Entry d) {
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
            fileHandler.saveResumeTorrent(new TorrentInfo(torrent));
        }
    }


    private void ConvertFuzeFile() throws IOException {
        File dir = new File(ctx.homeDir.getParent(), "azureus");
        File file = new File(dir, "downloads.config");

        if (file.exists()) {
            Entry configEntry = Entry.bdecode(file);
            List<Entry> downloads = configEntry.dictionary().get("downloads").list();

            for (Entry d : downloads) {
                try {
                    parseVuzeDownload(d);
                } catch (Exception e) {
                    LOG.error("Error restoring vuze torrent download", e);
                }
            }

            file.delete();
        }
    }


    public void runNextRestoreDownloadTask() {
        RestoreDownloadTask task = null;
        try {
            if (!restoreDownloadsQueue.isEmpty()) {
                task = restoreDownloadsQueue.poll();
            }
        } catch (Exception e) {
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
            } catch (Exception e) {
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
