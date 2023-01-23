package club.sk1er.patcher;

import cc.polyfrost.oneconfig.events.EventManager;
import cc.polyfrost.oneconfig.events.event.Stage;
import cc.polyfrost.oneconfig.events.event.TickEvent;
import cc.polyfrost.oneconfig.libs.eventbus.Subscribe;
import cc.polyfrost.oneconfig.libs.universal.UDesktop;
import cc.polyfrost.oneconfig.platform.LoaderPlatform;
import cc.polyfrost.oneconfig.platform.Platform;
import cc.polyfrost.oneconfig.utils.NetworkUtils;
import cc.polyfrost.oneconfig.utils.Notifications;
import cc.polyfrost.oneconfig.utils.commands.CommandManager;
import club.sk1er.patcher.asm.render.screen.GuiChatTransformer;
import club.sk1er.patcher.commands.PatcherCommand;
import club.sk1er.patcher.config.PatcherConfig;
import club.sk1er.patcher.config.PatcherSoundConfig;
import club.sk1er.patcher.ducks.FontRendererExt;
import club.sk1er.patcher.hooks.EntityRendererHook;
import club.sk1er.patcher.hooks.MinecraftHook;
import club.sk1er.patcher.mixins.features.network.packet.C01PacketChatMessageMixin_ExtendedChatLength;
import club.sk1er.patcher.render.ScreenshotPreview;
import club.sk1er.patcher.screen.PatcherMenuEditor;
import club.sk1er.patcher.screen.render.caching.HUDCaching;
import club.sk1er.patcher.screen.render.overlay.ArmorStatusRenderer;
import club.sk1er.patcher.screen.render.overlay.ImagePreview;
import club.sk1er.patcher.screen.render.overlay.metrics.MetricsRenderer;
import club.sk1er.patcher.screen.render.title.TitleFix;
import club.sk1er.patcher.tweaker.PatcherTweaker;
import club.sk1er.patcher.util.chat.ChatHandler;
import club.sk1er.patcher.util.enhancement.EnhancementManager;
import club.sk1er.patcher.util.enhancement.ReloadListener;
import club.sk1er.patcher.util.fov.FovHandler;
import club.sk1er.patcher.util.keybind.FunctionKeyChanger;
import club.sk1er.patcher.util.keybind.KeybindDropModifier;
import club.sk1er.patcher.util.keybind.MousePerspectiveKeybindHandler;
import club.sk1er.patcher.util.keybind.linux.LinuxKeybindFix;
import club.sk1er.patcher.util.screenshot.AsyncScreenshots;
import club.sk1er.patcher.util.status.ProtocolVersionDetector;
import club.sk1er.patcher.util.world.SavesWatcher;
import club.sk1er.patcher.util.world.render.culling.EntityCulling;
import club.sk1er.patcher.util.world.render.entity.EntityRendering;
import club.sk1er.patcher.util.world.sound.SoundHandler;
import club.sk1er.patcher.util.world.sound.audioswitcher.AudioSwitcher;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.settings.KeyBinding;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.net.URI;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Patcher {

    public static Patcher instance;

    Patcher() {
        instance = this;
    }

    // normal versions will be "1.x.x"
    // betas will be "1.x.x+beta-y" / "1.x.x+branch_beta-y"
    // rcs will be 1.x.x+rc-y
    // extra branches will be 1.x.x+branch-y
    public static final String VERSION = "1.8.6+oneconfig_alpha-2";

    private final Logger logger = LogManager.getLogger("Patcher");
    private final File logsDirectory = new File(Minecraft.getMinecraft().mcDataDir + File.separator + "logs" + File.separator);

    /**
     * Create a set of blacklisted servers, used for when a specific server doesn't allow for 1.8 clients to use
     * our 1.11 text length modifier (bring message length from 100 to 256, as done in 1.11 and above) {@link Patcher#addOrRemoveBlacklist(String)}.
     */
    private final Set<String> blacklistedServers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final File blacklistedServersFile = new File("./config/blacklisted_servers.txt");

    private final SavesWatcher savesWatcher = new SavesWatcher();
    private final AudioSwitcher audioSwitcher = new AudioSwitcher();

    private KeyBinding dropModifier, hideScreen, customDebug, clearShaders;

    private PatcherConfig patcherConfig;
    private PatcherSoundConfig patcherSoundConfig;

    private boolean loadedGalacticFontRenderer;

    private boolean isEssential;

    void onInit(Consumer<List<KeyBinding>> keybindRegisterer) {
        keybindRegisterer.accept(
            Arrays.asList(
                dropModifier = new KeybindDropModifier(),
                hideScreen = new FunctionKeyChanger.KeybindHideScreen(),
                customDebug = new FunctionKeyChanger.KeybindCustomDebug(),
                clearShaders = new FunctionKeyChanger.KeybindClearShaders()
            )
        );

        patcherConfig = PatcherConfig.INSTANCE;
        patcherSoundConfig = new PatcherSoundConfig(null, null);

        SoundHandler soundHandler = new SoundHandler();
        IReloadableResourceManager resourceManager = (IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager();
        resourceManager.registerReloadListener(soundHandler);
        resourceManager.registerReloadListener(new ReloadListener());

        registerCommands(
            new PatcherCommand(),
            new AsyncScreenshots.FavoriteScreenshot(), new AsyncScreenshots.DeleteScreenshot(),
            new AsyncScreenshots.UploadScreenshot(), new AsyncScreenshots.CopyScreenshot(),
            new AsyncScreenshots.ScreenshotsFolder()
        );

        registerOneconfigEvents(
            this, audioSwitcher, soundHandler, new EntityRendering(),
            new FovHandler(), new ChatHandler(), new EntityCulling(),
            new ArmorStatusRenderer(), new PatcherMenuEditor(),
            new ImagePreview(), new TitleFix(), new LinuxKeybindFix(),
            new MetricsRenderer(), new HUDCaching(), new EntityRendererHook(),
            new MousePerspectiveKeybindHandler(), MinecraftHook.INSTANCE,
            ScreenshotPreview.INSTANCE
        );

        checkLogs();
        loadBlacklistedServers();
        fixSettings();

        this.savesWatcher.watch();
    }

    void onPostInit() {
        if (!loadedGalacticFontRenderer) {
            loadedGalacticFontRenderer = true;
            FontRenderer galacticFontRenderer = Minecraft.getMinecraft().standardGalacticFontRenderer;
            if (galacticFontRenderer instanceof FontRendererExt) {
                ((FontRendererExt) galacticFontRenderer).patcher$getFontRendererHook().create();
            }
        }
        isEssential = Platform.getLoaderPlatform().isModLoaded("essential");
    }

    void onLoadComplete() {
        List<LoaderPlatform.ActiveMod> activeModList = Platform.getLoaderPlatform().getLoadedMods();
        Notifications notifications = Notifications.INSTANCE;
        this.detectIncompatibilities(activeModList, notifications);
        this.detectReplacements(activeModList, notifications);

        long time = (System.currentTimeMillis() - PatcherTweaker.clientLoadTime) / 1000L;
        if (PatcherConfig.startupNotification) {
            notifications.send("Minecraft Startup", "Minecraft started in " + time + " seconds.");
        }

        logger.info("Minecraft started in {} seconds.", time);

        //#if FORGE==1
        String mcVersion = net.minecraftforge.common.ForgeVersion.mcVersion;
        String forgeVersion = net.minecraftforge.common.ForgeVersion.getVersion();
        //noinspection ConstantConditions
        if (!mcVersion.equals("1.8.9") || forgeVersion.contains("2318")) return;
        notifications.send("Patcher", "Outdated Forge has been detected (" + forgeVersion + "). " +
            "Click to open the Forge website to download the latest version.", 30000f, () -> {
            String updateLink = "https://files.minecraftforge.net/net/minecraftforge/forge/index_1.8.9.html";
            try {
                UDesktop.browse(URI.create(updateLink));
            } catch (Exception openException) {
                this.logger.error("Failed to open Forge website.", openException);
                notifications.send("Patcher", "Failed to open Forge website. Link is now copied to your clipboard.");
                try {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(updateLink), null);
                } catch (Exception clipboardException) {
                    // there is no hope
                    this.logger.error("Failed to copy Forge website to clipboard.", clipboardException);
                    notifications.send("Patcher", "Failed to copy Forge website to clipboard.");
                }
            }
        });
        //#endif
    }

    //#if MC==10809

    /**
     * Runs when the user connects to a server.
     * Goes through the process of checking the current state of the server.
     * <p>
     * If the server is local, return and set the chat length to 256, as we modify the client to allow for
     * 256 message length in singleplayer through Mixins in {@link C01PacketChatMessageMixin_ExtendedChatLength}.
     * <p>
     * If the server is blacklisted, return and set the chat length to 100, as that server does not support 256 long
     * chat messages, and was manually blacklisted by the player.
     * <p>
     * If the server is not local nor blacklisted, check the servers protocol and see if it supports 315, aka 1.11.
     * If it does, then set the message length max to 256, otherwise return to 100.
     *
     * @param isLocal Whether the server is local or not.
     */
    public void connectToServer(boolean isLocal) {
        //TODO: legacy-fabric: HOOK
        if (isLocal) {
            GuiChatTransformer.maxChatLength = 256;
            return;
        }

        String serverIP = Minecraft.getMinecraft().getCurrentServerData().serverIP;
        if (serverIP == null || blacklistedServers.contains(serverIP)) {
            GuiChatTransformer.maxChatLength = 100;
            return;
        }

        boolean compatible = ProtocolVersionDetector.instance.isCompatibleWithVersion(
            serverIP,
            315 // 1.11
        );

        GuiChatTransformer.maxChatLength = compatible ? 256 : 100;
    }
    //#endif

    @Subscribe
    public void clientTick(TickEvent event) {
        if (event.stage == Stage.START) EnhancementManager.getInstance().tick();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void checkLogs() {
        if (PatcherConfig.logOptimizer && logsDirectory.exists()) {
            File[] files = logsDirectory.listFiles();
            if (files == null) return;

            for (File file : files) {
                if (file.getName().endsWith("log.gz") && file.lastModified() <= (System.currentTimeMillis() - PatcherConfig.logOptimizerLength * 86400000L)) {
                    file.delete();
                }
            }
        }
    }

    private void registerKeybinds(KeyBinding... keybinds) {
        for (KeyBinding keybind : keybinds) {
            Minecraft.getMinecraft().gameSettings.keyBindings =
                ArrayUtils.add(Minecraft.getMinecraft().gameSettings.keyBindings, keybind);
        }
    }

    private void registerOneconfigEvents(Object... handlers) {
        for (Object eventHandler : handlers) {
            EventManager.INSTANCE.register(eventHandler);
        }
    }

    private void registerCommands(Object... commands) {
        for (Object command : commands) {
            CommandManager.register(command);
        }
    }

    private boolean isServerBlacklisted(String ip) {
        if (ip == null) return false;
        String trim = ip.trim();
        return !trim.isEmpty() && blacklistedServers.contains(trim);
    }

    public boolean addOrRemoveBlacklist(String input) {
        if (input == null || input.isEmpty() || input.trim().isEmpty()) {
            return false;
        } else {
            input = input.trim();

            if (isServerBlacklisted(input)) {
                blacklistedServers.remove(input);
                return false;
            } else {
                blacklistedServers.add(input);
                return true;
            }
        }
    }

    public void saveBlacklistedServers() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(blacklistedServersFile))) {
            File parentFile = blacklistedServersFile.getParentFile();
            if (!parentFile.exists() && !parentFile.mkdirs()) {
                return;
            }

            if (!blacklistedServersFile.exists() && !blacklistedServersFile.createNewFile()) {
                return;
            }

            for (String server : blacklistedServers) {
                writer.write(server + System.lineSeparator());
            }
        } catch (IOException e) {
            logger.error("Failed to save blacklisted servers.", e);
        }
    }

    private void loadBlacklistedServers() {
        if (!blacklistedServersFile.exists()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(blacklistedServersFile))) {
            String servers;

            while ((servers = reader.readLine()) != null) {
                blacklistedServers.add(servers);
            }
        } catch (IOException e) {
            logger.error("Failed to load blacklisted servers.", e);
        }
    }

    private void fixSettings() {
        if (PatcherConfig.customZoomSensitivity > 1.0F) PatcherConfig.customZoomSensitivity = 1.0F;
        if (PatcherConfig.tabOpacity > 1.0F) PatcherConfig.tabOpacity = 1.0F;
        if (PatcherConfig.imagePreviewWidth > 1.0F) PatcherConfig.imagePreviewWidth = 0.5F;
        if (PatcherConfig.previewScale > 1.0F) PatcherConfig.previewScale = 1.0F;
        if (PatcherConfig.unfocusedFPSAmount < 15) PatcherConfig.unfocusedFPSAmount = 15;
        if (PatcherConfig.fireOverlayHeight < -0.5F || PatcherConfig.fireOverlayHeight > 1.5F) {
            PatcherConfig.fireOverlayHeight = 0.0F;
        }

        this.forceSaveConfig();
    }

    private void detectIncompatibilities(List<LoaderPlatform.ActiveMod> activeModList, Notifications notifications) {
        for (LoaderPlatform.ActiveMod container : activeModList) {
            String modId = container.id;
            String baseMessage = container.name + " has been detected. ";
            if (PatcherConfig.entityCulling && modId.equals("enhancements")) {
                notifications.send("Patcher", baseMessage + "Entity Culling is now disabled.");
                PatcherConfig.entityCulling = false;
            }

            if ((modId.equals("labymod") || modId.equals("enhancements")) || modId.equals("hychat")) {
                if (PatcherConfig.compactChat) {
                    notifications.send("Patcher", baseMessage + "Compact Chat is now disabled.");
                    PatcherConfig.compactChat = false;
                }

                if (PatcherConfig.chatPosition) {
                    notifications.send("Patcher", baseMessage + "Chat Position is now disabled.");
                    PatcherConfig.chatPosition = false;
                }
            }

            if (PatcherConfig.optimizedFontRenderer && modId.equals("smoothfont")) {
                notifications.send("Patcher", baseMessage + "Optimized Font Renderer is now disabled.");
                PatcherConfig.optimizedFontRenderer = false;
            }
        }

        this.forceSaveConfig();
    }

    private void detectReplacements(List<LoaderPlatform.ActiveMod> activeModList, Notifications notifications) {
        JsonObject replacedMods;
        try { // todo: replaced an async thing but i think its fine because get() pauses the game thread anyways i think???
            replacedMods = NetworkUtils.getJsonElement("https://static.sk1er.club/patcher/duplicate_mods.json").getAsJsonObject();
        } catch (Exception e) {
            logger.error("Failed to fetch list of replaced mods at \"https://static.sk1er.club/patcher/duplicate_mods.json\".", e);
            return;
        }

        if (replacedMods == null) return;
        Set<String> replacements = new HashSet<>();
        Set<String> modids = replacedMods.entrySet().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
        for (LoaderPlatform.ActiveMod modContainer : activeModList) {
            if (modids.contains(modContainer.id)) {
                replacements.add(modContainer.name);
            }
        }

        if (!replacements.isEmpty()) {
            for (String replacement : replacements) {
                notifications.send("Patcher", replacement + " can be removed as it is replaced by Patcher.", 6f);
            }
        }
    }

    public PatcherConfig getPatcherConfig() {
        return patcherConfig;
    }

    public PatcherSoundConfig getPatcherSoundConfig() {
        return patcherSoundConfig;
    }

    public Logger getLogger() {
        return logger;
    }

    public KeyBinding getDropModifier() {
        return dropModifier;
    }

    public KeyBinding getHideScreen() {
        return hideScreen;
    }

    public KeyBinding getCustomDebug() {
        return customDebug;
    }

    public KeyBinding getClearShaders() {
        return clearShaders;
    }

    public AudioSwitcher getAudioSwitcher() {
        return audioSwitcher;
    }

    public void forceSaveConfig() {
        this.patcherConfig.save();
    }

    public boolean isEssential() {
        return isEssential;
    }
}
