package io.github.codeutilities.features.external;

import com.jagrosh.discordipc.IPCClient;
import com.jagrosh.discordipc.IPCListener;
import com.jagrosh.discordipc.entities.RichPresence;
import com.jagrosh.discordipc.exceptions.NoDiscordClientException;
import io.github.codeutilities.CodeUtilities;
import io.github.codeutilities.config.CodeUtilsConfig;
import io.github.codeutilities.events.register.ReceiveChatMessageEvent;
import io.github.codeutilities.util.file.ILoader;
import io.github.codeutilities.util.networking.DFInfo;
import net.minecraft.client.MinecraftClient;
import org.apache.logging.log4j.Level;

import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DFDiscordRPC implements ILoader {

    // this looks weird
    private static final String EMPTY = "                                       ";
    public static boolean locating = false;
    public static boolean delayRPC = false;

    public static boolean supportSession = false;
    public static RichPresence.Builder builder;
    private static DFDiscordRPC instance;
    private static boolean firstLocate = true;
    private static boolean firstUpdate = true;
    private static String oldMode = "";
    private static OffsetDateTime time;
    private static IPCClient client;
    private DFRPCThread thread;

    public DFDiscordRPC() {
        instance = this;
    }

    public static DFDiscordRPC getInstance() {
        return instance;
    }

    @Override
    public void load() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CodeUtilities.log(Level.INFO, "Closing Discord hook.");
            try {
                client.close();
            } catch (Exception e) {
                CodeUtilities.log(Level.ERROR, "Error while closing Discord hook.");
            }
        }));

        client = new IPCClient(813925725718577202L);
        client.setListener(new IPCListener() {
            @Override
            public void onReady(IPCClient client) {
                RichPresence.Builder builder = new RichPresence.Builder();
                builder.setDetails("Playing");
                DFDiscordRPC.builder = builder;
            }
        });

        ExecutorService discordService = Executors.newSingleThreadExecutor();
        this.thread = new DFRPCThread();
        discordService.submit(thread);
    }

    private void update() {
        RichPresence.Builder presence = new RichPresence.Builder();
        String mode = "spawn";

        if (ReceiveChatMessageEvent.dfrpcMsg.startsWith(EMPTY + "\nYou are currently at spawn.\n")) {
            presence.setDetails("At spawn");
            presence.setState(ReceiveChatMessageEvent.dfrpcMsg.replaceFirst("^                                       \n" +
                    "You are currently at spawn.\n", "").replaceFirst("^→ Server: ", "").replaceFirst("\n" +
                    "                                       $", ""));
            if (supportSession) presence.setSmallImage("supportsession", "In Support Session");
            else presence.setSmallImage(null, null);

            String state = ReceiveChatMessageEvent.dfrpcMsg;
            state = state.replaceFirst("^ {39}\nYou are currently at spawn.\n", "")
                    .replaceFirst("^→ Server: ", "")
                    .replaceFirst("\n {39}$", "");

            presence.setState(state);
            presence.setSmallImage(null, null);
            presence.setLargeImage("diamondfirelogo", "mcdiamondfire.com");
        } else {
            // PLOT ID
            Pattern pattern = Pattern.compile("\\[[0-9]+]\n");
            Matcher matcher = pattern.matcher(ReceiveChatMessageEvent.dfrpcMsg);
            String id = "";
            while (matcher.find()) {
                id = matcher.group();
            }
            id = id.replaceAll("[\\[\\]\n]", "");

            // PLOT NODE
            pattern = Pattern.compile("Node ([0-9]|Beta)\n");
            matcher = pattern.matcher(ReceiveChatMessageEvent.dfrpcMsg);
            String node = "";
            while (matcher.find()) {
                node = matcher.group();
            }

            // PLOT NAME
            pattern = Pattern.compile("\n\n→ .+ \\[[0-9]+]\n");
            matcher = pattern.matcher(ReceiveChatMessageEvent.dfrpcMsg);
            String name = "";
            while (matcher.find()) {
                name = matcher.group();
            }
            name = name.replaceAll("(^\n\n→ )|( \\[[0-9]+]\n$)", "");

            // CUSTOM STATUS
            String customStatus = "";
            if (DFInfo.currentState == DFInfo.State.PLAY) {
                pattern = Pattern.compile("\n→ ");
                matcher = pattern.matcher(ReceiveChatMessageEvent.dfrpcMsg);
                int headerAmt = 0;
                while (matcher.find()) headerAmt++;
                if (headerAmt == 4) {
                    customStatus = ReceiveChatMessageEvent.dfrpcMsg
                            .replaceFirst("^.*\n.*\n\n→ .*\n→ ", "");
                    pattern = Pattern.compile("^.*");
                    matcher = pattern.matcher(customStatus);
                    while (matcher.find()) {
                        customStatus = matcher.group();
                    }
                }
            }

            // BUILD RICH PRESENCE
            presence.setState("Plot ID: " + id + " - " + node);
            presence.setDetails(name + " ");

            if (ReceiveChatMessageEvent.dfrpcMsg.startsWith(EMPTY + "\nYou are currently playing on:")) {
                if (supportSession) presence.setSmallImage("supportsession", "In Support Session (Playing)");
                presence.setSmallImage("modeplay", "Playing");
                presence.setLargeImage("diamondfirelogo", customStatus.equals("") ? "mcdiamondfire.com" : customStatus);
                mode = "play";
            } else if (ReceiveChatMessageEvent.dfrpcMsg.startsWith(EMPTY + "\nYou are currently building on:")) {
                if (supportSession) presence.setSmallImage("supportsession", "In Support Session (Building)");
                presence.setSmallImage("modebuild", "Building");
                presence.setLargeImage("diamondfirelogo", "mcdiamondfire.com");
                mode = "build";
            } else if (ReceiveChatMessageEvent.dfrpcMsg.startsWith(EMPTY + "\nYou are currently coding on:")) {
                if (supportSession) presence.setSmallImage("supportsession", "In Support Session (Coding)");
                presence.setSmallImage("modedev", "Coding");
                presence.setLargeImage("diamondfirelogo", "mcdiamondfire.com");
                mode = "dev";
            }
        }

        if (!oldMode.equals(mode)) firstUpdate = true;

        if (firstUpdate) {
            time = OffsetDateTime.now();
        }
        if (CodeUtilsConfig.getBoolean("discordRPCShowElapsed")) presence.setStartTimestamp(time);
        oldMode = mode;

        if (CodeUtilsConfig.getBoolean("discordRPC")) client.sendRichPresence(presence.build());
    }

    public DFRPCThread getThread() {
        return thread;
    }

    public class DFRPCThread extends Thread {
        final MinecraftClient mc = MinecraftClient.getInstance();

        @Override
        public void run() {
            String oldState = "Not on DF";
            int i = 0;

            while (true) {

                if (DFInfo.isOnDF() && !delayRPC) {
                    if (!String.valueOf(DFInfo.currentState).equals(oldState)) {
                        locateRequest();
                    } else {
                        if (i % 30 == 0 && DFInfo.currentState == DFInfo.State.PLAY) locateRequest();
                    }
                } else {
                    firstLocate = true;
                    firstUpdate = true;
                    try {
                        client.close();
                    } catch (Exception ignored) {
                    }
                }

                if (!CodeUtilsConfig.getBoolean("discordRPC")) {
                    firstLocate = true;
                    firstUpdate = true;
                    try {
                        client.close();
                    } catch (Exception ignored) {
                    }
                }

                if (DFInfo.isOnDF()) {
                    oldState = String.valueOf(DFInfo.currentState);
                } else {
                    oldState = "Not on DF";
                    supportSession = false;
                }

                if (delayRPC) {
                    delayRPC = false;
                    try {
                        DFRPCThread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                try {
                    DFRPCThread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                i++;

            }
        }

        public void locateRequest() {
            if (mc.player != null) {
                if (CodeUtilsConfig.getBoolean("discordRPC")) {
                    mc.player.sendChatMessage("/locate");
                }
                locating = true;
                for (int i = 0; i < CodeUtilsConfig.getLong("discordRPCTimeout"); i++) {
                    try {
                        DFRPCThread.sleep(1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (!locating) break;
                }
                locating = false;
            }

            if (firstLocate) {
                try {
                    client.connect();
                } catch (NoDiscordClientException ignored) {
                }
                update();
                firstLocate = false;
            } else {
                update();
                firstUpdate = false;
            }
            //CodeUtilities.log(Level.INFO, "----------- RPC Updated! Status: " + client.getStatus());
        }
    }
}