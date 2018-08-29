package com.frostwire.bittorrent;

import com.frostwire.jlibtorrent.AlertListener;
import com.frostwire.jlibtorrent.SessionManager;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.alerts.*;
import com.frostwire.util.Logger;

import static com.frostwire.jlibtorrent.alerts.AlertType.*;

public final class InnerListener extends SessionManager implements AlertListener  {

    private static final Logger LOG = Logger.getLogger(InnerListener.class);

    private BTEngineListener listener;
    private BTEngine BTEngineInstance;

    private static final int[] INNER_LISTENER_TYPES = new int[]{
            ADD_TORRENT.swig(),
            LISTEN_SUCCEEDED.swig(),
            LISTEN_FAILED.swig(),
            EXTERNAL_IP.swig(),
            FASTRESUME_REJECTED.swig(),
            TORRENT_LOG.swig(),
            PEER_LOG.swig(),
            AlertType.LOG.swig()
    };

    @Override
    public int[] types()
    {
        return INNER_LISTENER_TYPES;
    }

    public InnerListener(BTEngine btEngine)
    {
        this.BTEngineInstance = btEngine;
    }

    public BTEngineListener getListener()
    {
        return listener;
    }

    public void setListener(BTEngineListener listener)
    {
        this.listener = listener;
    }

    @Override
    public void alert(Alert<?> alert)
    {
        AlertType type = alert.type();

        switch (type) {
            case ADD_TORRENT:
                TorrentAlert<?> torrentAlert = (TorrentAlert<?>) alert;
                fireDownloadAdded(torrentAlert);
                BTEngineInstance.runNextRestoreDownloadTask();
                break;
            case LISTEN_SUCCEEDED:
                onListenSucceeded((ListenSucceededAlert) alert);
                break;
            case LISTEN_FAILED:
                onListenFailed((ListenFailedAlert) alert);
                break;
            case EXTERNAL_IP:
                onExternalIpAlert((ExternalIpAlert) alert);
                break;
            case FASTRESUME_REJECTED:
                onFastresumeRejected((FastresumeRejectedAlert) alert);
                break;
            case TORRENT_LOG:
            case PEER_LOG:
            case LOG:
                printAlert(alert);
                break;
        }
    }

    private void printAlert(Alert alert) {
        System.out.println("Log: " + alert);
    }


    public void fireDownloadAdded(TorrentAlert<?> alert) {
        try {
            TorrentHandle th = find(alert.handle().infoHash());
            if (th != null) {
                BTDownload dl = new BTDownload(BTEngineInstance, th);
                if (listener != null) {
                    listener.downloadAdded(BTEngineInstance, dl);
                }
            } else {
                LOG.info("torrent was not successfully added");
            }
        } catch (Throwable e) {
            LOG.error("Unable to create and/or notify the new download", e);
        }
    }

    public void fireDownloadUpdate(TorrentHandle th) {
        try {
            BTDownload dl = new BTDownload(BTEngineInstance, th);
            if (listener != null) {
                listener.downloadUpdate(BTEngineInstance, dl);
            }
        } catch (Throwable e) {
            LOG.error("Unable to notify update the a download", e);
        }
    }

    private void fireStarted() {
        if (listener != null) {
            listener.started(BTEngineInstance);
        }
    }

    private void fireStopped() {
        if (listener != null) {
            listener.stopped(BTEngineInstance);
        }
    }

    private void onListenSucceeded(ListenSucceededAlert alert) {
        try {
            String endp = alert.address() + ":" + alert.port();
            String s = "endpoint: " + endp + " type:" + alert.socketType();
            LOG.info("Listen succeeded on " + s);
        } catch (Throwable e) {
            LOG.error("Error adding listen endpoint to internal list", e);
        }
    }

    private void onListenFailed(ListenFailedAlert alert) {
        String endp = alert.address() + ":" + alert.port();
        String s = "endpoint: " + endp + " type:" + alert.socketType();
        String message = alert.error().message();
        LOG.info("Listen failed on " + s + " (error: " + message + ")");
    }

    private void onExternalIpAlert(ExternalIpAlert alert) {
        try {
            // libtorrent perform all kind of tests
            // to avoid non usable addresses
            String address = alert.externalAddress().toString();
            LOG.info("External IP: " + address);
        } catch (Throwable e) {
            LOG.error("Error saving reported external ip", e);
        }
    }

    private void onFastresumeRejected(FastresumeRejectedAlert alert) {
        try {
            LOG.warn("Failed to load fastresume data, path: " + alert.filePath() +
                    ", operation: " + alert.operation() + ", error: " + alert.error().message());
        } catch (Throwable e) {
            LOG.error("Error logging fastresume rejected alert", e);
        }
    }


    protected void onAfterStart() {
        fireStarted();
    }

    protected void onAfterStop() {
        fireStopped();
    }


}