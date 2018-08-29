package com.frostwire.bittorrent;

import com.frostwire.jlibtorrent.SessionManager;
import com.frostwire.jlibtorrent.SessionParams;
import com.frostwire.jlibtorrent.SettingsPack;
import com.frostwire.jlibtorrent.Vectors;
import com.frostwire.jlibtorrent.swig.*;
import com.frostwire.util.Logger;
import org.apache.commons.io.FileUtils;

import java.io.File;

public class BTSettingsManager extends SessionManager {

    private static final Logger LOG = Logger.getLogger(BTSettingsManager.class);
    private String STATE_VERSION_KEY;
    private String STATE_VERSION_VALUE ;
    public static BTContext ctx;

    public BTSettingsManager(String versionKey, String versionValue)
    {
        this.STATE_VERSION_KEY = versionKey;
        this.STATE_VERSION_VALUE = versionValue;
    }

    public SessionParams loadSettings() {
        try {
            File f = settingsFile();
            if (f.exists()) {
                byte[] data = FileUtils.readFileToByteArray(f);
                byte_vector buffer = Vectors.bytes2byte_vector(data);
                bdecode_node n = new bdecode_node();
                error_code ec = new error_code();
                int ret = bdecode_node.bdecode(buffer, n, ec);

                if (ret == 0) {
                    String stateVersion = n.dict_find_string_value_s(STATE_VERSION_KEY);
                    if (!STATE_VERSION_VALUE.equals(stateVersion)) {
                        return defaultParams();
                    }

                    session_params params = libtorrent.read_session_params(n);
                    buffer.clear(); // prevents GC
                    return new SessionParams(params);
                } else {
                    LOG.error("Can't decode session state data: " + ec.message());
                    return defaultParams();
                }
            } else {
                return defaultParams();
            }
        } catch (Throwable e) {
            LOG.error("Error loading session state", e);
            return defaultParams();
        }
    }

    public void saveSettings() {
        if (swig() == null) {
            return;
        }

        try {
            byte[] data = saveState();
            FileUtils.writeByteArrayToFile(settingsFile(), data);
        } catch (Throwable e) {
            LOG.error("Error saving session state", e);
        }
    }

    File settingsFile() {
        return new File(ctx.homeDir, "settings.dat");
    }

    private SessionParams defaultParams() {
        SettingsPack sp = defaultSettings();
        SessionParams params = new SessionParams(sp);
        return params;
    }

    private SettingsPack defaultSettings() {
        SettingsPack sp = new SettingsPack();

        sp.broadcastLSD(true);

        if (ctx.optimizeMemory) {
            int maxQueuedDiskBytes = sp.maxQueuedDiskBytes();
            sp.maxQueuedDiskBytes(maxQueuedDiskBytes / 2);
            int sendBufferWatermark = sp.sendBufferWatermark();
            sp.sendBufferWatermark(sendBufferWatermark / 2);
            sp.cacheSize(256);
            sp.activeDownloads(4);
            sp.activeSeeds(4);
            sp.maxPeerlistSize(200);
            //sp.setGuidedReadCache(true);
            sp.tickInterval(1000);
            sp.inactivityTimeout(60);
            sp.seedingOutgoingConnections(false);
            sp.connectionsLimit(200);
        } else {
            sp.activeDownloads(10);
            sp.activeSeeds(10);
        }

        return sp;
    }


    public void revertToDefaultConfiguration() {
        if (swig() == null) {
            return;
        }

        SettingsPack sp = defaultSettings();
        applySettings(sp);
    }
}
