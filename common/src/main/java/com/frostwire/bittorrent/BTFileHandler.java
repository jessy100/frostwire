package com.frostwire.bittorrent;

import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.Vectors;
import com.frostwire.jlibtorrent.swig.entry;
import com.frostwire.platform.FileSystem;
import com.frostwire.platform.Platforms;
import com.frostwire.util.Logger;
import org.apache.commons.io.FileUtils;

import java.io.File;

public class BTFileHandler {

    private static final String TORRENT_ORIG_PATH_KEY = "torrent_orig_path";
    private static BTContext ctx;
    private static final Logger LOG = Logger.getLogger(BTFileHandler.class);


    File resumeTorrentFile(String infoHash) {
        return new File(ctx.homeDir, infoHash + ".torrent");
    }

    File torrentFile(String name) {
        return new File(ctx.torrentsDir, name + ".torrent");
    }

    File resumeDataFile(String infoHash) {
        return new File(ctx.homeDir, infoHash + ".resume");
    }

    File readTorrentPath(String infoHash) {
        File torrent = null;

        try {
            byte[] arr = FileUtils.readFileToByteArray(resumeTorrentFile(infoHash));
            entry e = entry.bdecode(Vectors.bytes2byte_vector(arr));
            torrent = new File(e.dict().get(TORRENT_ORIG_PATH_KEY).string());
        } catch (Throwable e) {
            // can't recover original torrent path
        }

        return torrent;
    }

    File readSavePath(String infoHash) {
        File savePath = null;

        try {
            byte[] arr = FileUtils.readFileToByteArray(resumeDataFile(infoHash));
            entry e = entry.bdecode(Vectors.bytes2byte_vector(arr));
            savePath = new File(e.dict().get("save_path").string());
        } catch (Throwable e) {
            // can't recover original torrent path
        }

        return savePath;
    }

    public void saveTorrent(TorrentInfo ti) {
        File torrentFile;

        try {
            String name = getEscapedFilename(ti);

            torrentFile = torrentFile(name);
            byte[] arr = ti.toEntry().bencode();

            FileSystem fs = Platforms.get().fileSystem();
            fs.write(torrentFile, arr);
            fs.scan(torrentFile);
        } catch (Throwable e) {
            LOG.warn("Error saving torrent info to file", e);
        }

    }

    public void saveResumeTorrent(TorrentInfo ti) {

        try {
            String name = getEscapedFilename(ti);

            entry e = ti.toEntry().swig();
            e.dict().set(TORRENT_ORIG_PATH_KEY, new entry(torrentFile(name).getAbsolutePath()));
            byte[] arr = Vectors.byte_vector2bytes(e.bencode());

            FileUtils.writeByteArrayToFile(resumeTorrentFile(ti.infoHash().toString()), arr);
        } catch (Throwable e) {
            LOG.warn("Error saving resume torrent", e);
        }
    }

    public String getEscapedFilename(TorrentInfo ti) {
        String name = ti.name();
        if (name == null || name.length() == 0) {
            name = ti.infoHash().toString();
        }
        return escapeFilename(name);
    }


    public File setupSaveDir(File saveDir) {
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

    private static String escapeFilename(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|\\[\\]]+", "_");
    }

}
