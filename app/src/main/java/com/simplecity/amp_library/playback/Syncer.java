package com.simplecity.amp_library.playback;

import android.os.Environment;

import com.simplecity.amp_library.model.Song;
import com.simplecity.amp_library.utils.DataManager;
import com.simplecity.amp_library.utils.FileHelper;
import com.simplecity.amp_library.utils.StringUtils;

import org.pircbotx.User;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ConnectEvent;
import org.pircbotx.hooks.events.IncomingFileTransferEvent;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.types.GenericMessageEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Stack;

import rx.Observable;

/// TODO: Synchronized shuffle order, in shuffle mode.
public class Syncer extends ListenerAdapter {
    public enum Command {
        Play,
        Pause,
        Stop,
        Next,
        Previous,
        Seek,
        QueueRemove,
        QueueSetPos,
        QueueNext,
        QueueAdd,
        QueueNow,
        QueueMove,
        QueueClear;

        public static Command parse(String s) {
            return Command.values()[Integer.parseInt(s)];
        }

        public String toString() {
            return String.valueOf(this.ordinal());
        }
    }

    private class NeededSong {
        int queuePos;
        int action;

        NeededSong(int pos, int action) {
            this.queuePos = pos;
            this.action = action;
        }
    }

    private final MusicService service;
    private Command lastCommand = null;
    private Queue<NeededSong> neededSongs;

    public Syncer(MusicService ms) {
        super();
        service = ms;
    }

    @Override
    public void onConnect(ConnectEvent event) {
        System.out.println("sm: You've connected to " + service.getSyncChannel() + "!");
        // TODO: Send this text as a notification.
    }

    @Override
    public void onJoin(JoinEvent event) {
        User user = event.getUser();
        // TODO: Send this text as a notification.
        if (user.getIdent().equals(service.getSyncUser())) {
            System.out.println("You joined the listening channel!");
            service.toggleSyncBroadcast(false);
            service.clearQueue();
            service.toggleSyncBroadcast(true);
            event.getChannel().getUsers().first().send().message("?s`q:req");
        } else {
            System.out.println("" + user.getNick() + " joined the listening channel.");
        }
    }

    @Override
    public void onPrivateMessage(PrivateMessageEvent event) {
        if (event.getMessage().equals("?s`q:req")) {
            StringBuilder msg = new StringBuilder();
            List<Song> q = service.getQueue();
            // get them the currently playing song, then add the rest of the future queue.
            msg.append("?s`");
            msg.append(Command.QueueNow.toString());
            Song curr = service.getSong();
            msg.append('`');
            msg.append(curr.name);
            msg.append('`');
            msg.append(curr.artistName);
            msg.append('`');
            msg.append(curr.albumName);

            msg.append("``?s`seek`");
            msg.append(service.getPosition());

            if (service.getQueuePosition() >= q.size() - 1) {
                msg.append("``?s`");
                msg.append(Command.QueueAdd.toString());
                for (int i = service.getQueuePosition(); i < q.size(); i++) {
                    Song s = q.get(i);
                    msg.append('`');
                    msg.append(s.name);
                    msg.append('`');
                    msg.append(s.artistName);
                    msg.append('`');
                    msg.append(s.albumName);
                }
            }

            event.respond(msg.toString());
        }
    }

    @Override
    public void onGenericMessage(GenericMessageEvent event) {
        // NOTE: Split commands with `` (double backtick)
        // NOTE: Split arguments with ` (single backtick)
        service.toggleSyncBroadcast(false); // turn off broadcasting user queue changes so we can internally sync the queue.

        if (event.getMessage().startsWith("?s`")) {
            // Split commands with ```
            String[] messages = event.getMessage().split("``");
            for (String msgStr : messages) {
                // Split arguments with ``
                String[] msg = msgStr.split("`"); // TODO: Pick the ideal separator unlikely to be in song metadata.
                // msg[0] == "?s"
                Command cmd = Command.parse(msg[1]);
                switch (cmd) {
                    case Play:
                        service.play(); break;
                    case Pause:
                        service.pause(); break;
                    case Stop:
                        service.stop(); break;
                    case Next:
                        service.next(); break;
                    case Previous:
                        service.prev(); break;
                    case QueueRemove:
                        // TODO: Implement removal with Song info rather than an index.
                        break;
                    case QueueSetPos: {
                        int pos = Integer.parseInt(msg[1]);
                        service.setQueuePosition(pos);
                        break;
                    }
                    case QueueMove:
                        int from = Integer.parseInt(msg[1]);
                        int to = Integer.parseInt(msg[2]);
                        int curr = service.getQueuePosition();
                        service.moveQueueItem(curr + from, curr + to);
                        break;
                    case QueueClear:
                        service.clearQueue(); break;
                    case Seek:
                        Long time = Long.parseLong(msg[2]); // time to seek to
                        service.seekTo(time);
                        break;
                    case QueueNow:
                    case QueueAdd:
                    case QueueNext:
                        this.lastCommand = cmd;

                        List<Song> songs = new ArrayList<>();
                        int songIdx = 0;

                        // Able to send a whole list of songs.
                        for (int i = 3; i < msg.length; i += 3) {
                            String name = msg[i];
                            String artist = msg[i + 1];
                            String album = msg[i + 2];

                            // check if we have the song already.
                            Song song = DataManager.getInstance().getSongsRelay()
                                    .map(ss -> ss.stream()
                                            .filter(s -> s.name.equalsIgnoreCase(name)
                                                    && s.artistName.equalsIgnoreCase(artist)
                                                    && s.albumName.equalsIgnoreCase(album))
                                            .findFirst()
                                            .orElse(null)).toBlocking().first();

                            if (song == null) {
                                // Need to download from them.
                                switch (cmd) {
                                    case QueueNext:
                                        // Queue index is from current.
                                        this.neededSongs.add(new NeededSong(service.getQueuePosition() + songIdx, MusicService.EnqueueAction.NEXT));
                                        break;
                                    case QueueAdd:
                                        // NOTE: Might need (- 1).
                                        this.neededSongs.add(new NeededSong(service.getQueue().size() + songIdx, MusicService.EnqueueAction.LAST));
                                        break;
                                    // TODO: QueueNow
                                }
                            } else {
                                songs.add(song);
                            }
                            songIdx += 1;
                        }

                        if (!songs.isEmpty()) {
                            service.toggleSyncBroadcast(false);
                            if (!songs.isEmpty()) {
                                this.lastCommand = null; // clear this command from history, and just queue the song.
                                switch (cmd) {
                                    case QueueNow:
                                        int pos = Integer.parseInt(msg[2]);
                                        service.open(songs, pos);
                                        break;
                                    case QueueAdd:
                                        service.enqueue(songs, MusicService.EnqueueAction.LAST);
                                        break;
                                    case QueueNext:
                                        service.enqueue(songs, MusicService.EnqueueAction.NEXT);
                                        break;
                                }
                            }
                            service.toggleSyncBroadcast(true);
                            // Otherwise, we don't have the song, so the other user will send it and we accept that transfer.
                        }
                        break;
                }
            }
        }
        
        service.toggleSyncBroadcast(true);
    }

    private File downloadNow(IncomingFileTransferEvent event) {
        File file = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            event.getSafeFilename()
        );

        try {
            event.acceptAndTransfer(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return file;
    }

    @Override
    public void onIncomingFileTransfer(IncomingFileTransferEvent event) throws IOException {
        service.toggleSyncBroadcast(false); // turn off broadcasting user queue changes so we can internally sync the queue.

        // TODO: Check when this is to be played?
        if (this.lastCommand == Command.QueueNow) {
            // Play this NOW! Basic, simple
            File file = this.downloadNow(event);
            service.openFile(file.getAbsolutePath(), () -> {
                service.play();
            });
        } else if (this.lastCommand == Command.QueueNext || this.lastCommand == Command.QueueAdd) {
            // TODO: Download the file passively (?)
            File file = this.downloadNow(event);
            try {
                Thread.sleep(300); // Wait 300ms for file to load into library.
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            FileHelper.getSong(file).toList().forEach(songs -> {
                switch (this.lastCommand) {
                    case QueueNext:
                        service.enqueue(songs, MusicService.EnqueueAction.NEXT);
                        break;
                    case QueueAdd:
                        service.enqueue(songs, MusicService.EnqueueAction.LAST);
                        break;
                }
            });
        } else {
            // No way to decline the file transfer.
            // Just let it time out.
        }

        service.toggleSyncBroadcast(true);
    }
}
