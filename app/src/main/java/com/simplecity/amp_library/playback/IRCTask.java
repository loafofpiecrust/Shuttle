package com.simplecity.amp_library.playback;

import android.os.AsyncTask;

import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.exception.IrcException;

import java.io.IOException;

/**
 * Created by snead on 5/30/17.
 */

public class IRCTask extends AsyncTask<MusicService, Void, PircBotX> {
    MusicService service;

    @Override
    protected PircBotX doInBackground(MusicService... params) {
        this.service = params[0];
        if (service == null) {
            return null;
        }

        // If the bot is already configured, just switch channels.
        if (service.ircBot != null) {
            service.ircBot.send().changeNick(service.getSyncUser());
            service.ircBot.getUserBot().getChannels().forEach(c -> c.send().part());
            service.ircBot.send().joinChannel(service.getSyncChannel());
            return service.ircBot;
        } else {
            // NEW: Connect to the IRC channel and listen for messages.
            //Configure what we want our bot to do
            Configuration ircConfig = new Configuration.Builder()
                    .setName(service.getSyncUser()) //Set the nick of the bot.
                    .setLogin(service.getSyncUser())
                    .setAutoNickChange(true)
                    .addServer("irc.freenode.net") //Join the freenode network
                    .addAutoJoinChannel(service.getSyncChannel()) //Join the official #pircbotx channel
                    .addListener(new Syncer(service)) //Add our listener that will be called on Events
                    .setDccAcceptTimeout(5000) // only keep file offers alive for 5 seconds.
                    .setAutoReconnect(true)
                    .setAutoReconnectDelay(3000)
                    .setAutoReconnectAttempts(5)
                    .setMaxLineLength(512)
                    .setMessageDelay(100)
                    .buildConfiguration();

            //Create our bot with the configuration
            PircBotX bot = new PircBotX(ircConfig);
            //Connect to the server
            service.ircBot = bot;
            try {
                bot.startBot();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (IrcException e) {
                e.printStackTrace();
            }
            return bot;
        }
    }

}
