package com.rexcantor64.triton;

import com.google.common.io.ByteStreams;
import com.rexcantor64.triton.bridge.BungeeBridgeManager;
import com.rexcantor64.triton.commands.bungee.MainCMD;
import com.rexcantor64.triton.commands.bungee.TwinCMD;
import com.rexcantor64.triton.language.item.LanguageSign;
import com.rexcantor64.triton.language.item.LanguageText;
import com.rexcantor64.triton.packetinterceptor.BungeeDecoder;
import com.rexcantor64.triton.packetinterceptor.BungeeListener;
import com.rexcantor64.triton.packetinterceptor.ProtocolLibListener;
import com.rexcantor64.triton.player.BungeeLanguagePlayer;
import com.rexcantor64.triton.plugin.PluginLoader;
import com.rexcantor64.triton.terminal.BungeeTerminalManager;
import com.rexcantor64.triton.terminal.Log4jInjector;
import com.rexcantor64.triton.utils.NMSUtils;
import io.netty.channel.Channel;
import lombok.val;
import lombok.var;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.netty.PipelineUtils;
import org.bstats.bungeecord.Metrics;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class BungeeMLP extends Triton {

    private ScheduledTask configRefreshTask;

    public BungeeMLP(PluginLoader loader) {
        super.loader = loader;
    }

    @Override
    public void onEnable() {
        instance = this;
        super.onEnable();

        Metrics metrics = new Metrics(loader.asBungee(), 5607);
        metrics.addCustomChart(new Metrics.SingleLineChart("active_placeholders",
                () -> Triton.get().getLanguageManager().getItemCount()));

        getBungeeCord().getPluginManager().registerListener(loader.asBungee(), new BungeeBridgeManager());
        getBungeeCord().registerChannel("triton:main");

        for (ProxiedPlayer p : getBungeeCord().getPlayers()) {
            BungeeLanguagePlayer lp = (BungeeLanguagePlayer) getPlayerManager().get(p.getUniqueId());
            injectPipeline(lp, p);
        }

        getBungeeCord().getPluginManager().registerCommand(loader.asBungee(), new MainCMD());
        getBungeeCord().getPluginManager().registerCommand(loader.asBungee(), new TwinCMD());

        sendConfigToEveryone();

        try {
            if (getConf().isTerminal())
                BungeeTerminalManager.injectTerminalFormatter();
        } catch (Error | Exception e) {
            try {
                if (getConf().isTerminal())
                    Log4jInjector.injectAppender();
            } catch (Error | Exception ignored) {
                getLogger()
                        .logError("Failed to inject terminal translations. Some forked BungeeCord servers might not " +
                                "work " +
                                "correctly. To hide this message, disable terminal translation on config.");
            }
        }
    }

    @Override
    public void reload() {
        super.reload();
        sendConfigToEveryone();
    }

    @Override
    protected void startConfigRefreshTask() {
        if (configRefreshTask != null) configRefreshTask.cancel();
        if (getConf().getConfigAutoRefresh() <= 0) return;
        configRefreshTask = getBungeeCord().getScheduler()
                .schedule(loader.asBungee(), this::reload, getConf().getConfigAutoRefresh(), TimeUnit.SECONDS);
    }

    // TODO Move this to a bridge manager class
    private void sendConfigToEveryone() {
        try {
            val languageOut = ByteStreams.newDataOutput();
            // Action 0 (send config)
            languageOut.writeUTF(Triton.get().getLanguageManager().getMainLanguage().getName());
            val languageList = Triton.get().getLanguageManager().getAllLanguages();
            languageOut.writeShort(languageList.size());
            for (val language : languageList) {
                languageOut.writeUTF(language.getName());
                languageOut.writeUTF(language.getRawDisplayName());
                languageOut.writeUTF(language.getFlagCode());
                languageOut.writeShort(language.getMinecraftCodes().size());
                for (val code : language.getMinecraftCodes())
                    languageOut.writeUTF(code);
            }

            // Send language files
            for (val info : getBungeeCord().getServers().values()) {
                var firstSend = true;
                var size = 0;
                var languageItemsOut = ByteStreams.newDataOutput();
                for (val collection : Triton.get().getStorage().getCollections().values())
                    for (val item : collection.getItems()) {
                        if (languageItemsOut.toByteArray().length > 29000) {
                            val out = ByteStreams.newDataOutput();
                            out.writeByte(0);
                            if (firstSend) {
                                firstSend = false;
                                out.writeBoolean(true);
                                out.write(languageOut.toByteArray());
                            } else {
                                out.writeBoolean(false);
                            }
                            out.writeInt(size);
                            out.write(languageItemsOut.toByteArray());
                            info.sendData("triton:main", out.toByteArray());
                            languageItemsOut = ByteStreams.newDataOutput();
                            size = 0;
                        }
                        // TODO use if instead of switch
                        switch (item.getType()) {
                            case TEXT:
                                val text = (LanguageText) item;
                                if (!text.belongsToServer(collection.getMetadata(), info.getName()))
                                    continue;
                                // Send type (2) (type 0, but with matches data
                                languageItemsOut.writeByte(2);
                                languageItemsOut.writeUTF(item.getKey());
                                var langSize2 = 0;
                                val langOut2 = ByteStreams.newDataOutput();
                                for (val lang : languageList) {
                                    val msg = text.getMessage(lang.getName());
                                    if (msg == null) continue;
                                    langOut2.writeUTF(lang.getName());
                                    langOut2.writeUTF(msg);
                                    langSize2++;
                                }
                                languageItemsOut.writeShort(langSize2);
                                languageItemsOut.write(langOut2.toByteArray());
                                if (text.getPatterns() != null) {
                                    languageItemsOut.writeShort(text.getPatterns().size());
                                    for (String s : text.getPatterns())
                                        languageItemsOut.writeUTF(s);
                                } else
                                    languageItemsOut.writeShort(0);
                                break;
                            case SIGN:
                                // Send type (1)
                                val sign = (LanguageSign) item;
                                languageItemsOut.writeByte(1);
                                languageItemsOut.writeUTF(item.getKey());

                                var locSize = 0;
                                val locOut = ByteStreams.newDataOutput();
                                for (val loc : sign.getLocations()) {
                                    if (loc.getServer() != null && !loc.getServer().equals(info.getName()))
                                        continue;
                                    locOut.writeUTF(loc.getWorld());
                                    locOut.writeInt(loc.getX());
                                    locOut.writeInt(loc.getY());
                                    locOut.writeInt(loc.getZ());
                                    locSize++;
                                }
                                languageItemsOut.writeShort(locSize);
                                languageItemsOut.write(locOut.toByteArray());
                                var langSize = 0;
                                val langOut = ByteStreams.newDataOutput();
                                for (val lang : languageList) {
                                    val lines = sign.getLines(lang.getName());
                                    if (lines == null) continue;
                                    langOut.writeUTF(lang.getName());
                                    langOut.writeUTF(lines[0]);
                                    langOut.writeUTF(lines[1]);
                                    langOut.writeUTF(lines[2]);
                                    langOut.writeUTF(lines[3]);
                                    langSize++;
                                }
                                languageItemsOut.writeShort(langSize);
                                languageItemsOut.write(langOut.toByteArray());
                                break;
                        }
                        size++;
                    }
                val out = ByteStreams.newDataOutput();
                out.writeByte(0);
                out.writeBoolean(firstSend);
                if (firstSend)
                    out.write(languageOut.toByteArray());
                out.writeInt(size);
                out.write(languageItemsOut.toByteArray());
                info.sendData("triton:main", out.toByteArray());
            }
        } catch (Exception e) {
            getLogger()
                    .logError("Failed to send config and language items to other servers! Not everything might work " +
                            "as " +
                            "expected! Error: %1", e.getMessage());
            if (Triton.get().getConf().getLogLevel() > 0)
                e.printStackTrace();
        }
    }

    public ProtocolLibListener getProtocolLibListener() {
        return null;
    }

    public File getDataFolder() {
        return loader.asBungee().getDataFolder();
    }

    @Override
    public String getVersion() {
        return loader.asBungee().getDescription().getVersion();
    }

    public void injectPipeline(BungeeLanguagePlayer lp, Connection p) {
        try {
            Object ch = NMSUtils.getDeclaredField(p, "ch");
            Method method = ch.getClass().getDeclaredMethod("getHandle");
            Channel channel = (Channel) method.invoke(ch, new Object[0]);
            channel.pipeline().addAfter(PipelineUtils.PACKET_DECODER, "triton-custom-decoder", new BungeeDecoder(lp));
            channel.pipeline()
                    .addAfter(PipelineUtils.PACKET_ENCODER, "triton-custom-encoder", new BungeeListener(lp));
        } catch (Exception e) {
            getBungeeCord().getLogger()
                    .log(Level.SEVERE, "[BungeePackets] Failed to inject client connection for " + lp.getUUID()
                            .toString());
        }
    }

    @Override
    public void runAsync(Runnable runnable) {
        getBungeeCord().getScheduler().runAsync(loader.asBungee(), runnable);
    }

    public ProxyServer getBungeeCord() {
        return loader.asBungee().getProxy();
    }
}
