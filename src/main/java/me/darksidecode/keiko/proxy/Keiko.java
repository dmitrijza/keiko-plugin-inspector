/*
 * Copyright (C) 2019-2021 German Vekhorev (DarksideCode)
 *
 * This file is part of Keiko Plugin Inspector.
 *
 * Keiko Plugin Inspector is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Keiko Plugin Inspector is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Keiko Plugin Inspector.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.darksidecode.keiko.proxy;

import lombok.Getter;
import me.darksidecode.keiko.config.ConfigurationLoader;
import me.darksidecode.keiko.config.GlobalConfig;
import me.darksidecode.keiko.config.InspectionsConfig;
import me.darksidecode.keiko.config.RuntimeProtectConfig;
import me.darksidecode.keiko.installation.KeikoInstaller;
import me.darksidecode.keiko.installation.KeikoUpdater;
import me.darksidecode.keiko.installation.MalformedVersionException;
import me.darksidecode.keiko.integrity.IntegrityChecker;
import me.darksidecode.keiko.io.KeikoLogger;
import me.darksidecode.keiko.registry.PluginContext;
import me.darksidecode.keiko.runtimeprotect.RuntimeProtect;
import me.darksidecode.keiko.staticanalysis.StaticAnalysisManager;
import me.darksidecode.keiko.staticanalysis.cache.LocalFileStorageCacheManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Properties;
import java.util.Timer;
import java.util.concurrent.TimeUnit;

public final class Keiko {

    private static final String LOGO =
            "\n\n" +
            "     _  __      _  _          \n" +
            "    | |/ / ___ (_)| | __ ___  \n" +
            "    | ' / / _ \\| || |/ // _ \\ \n" +
            "    | . \\|  __/| ||   <| (_) |\n" +
            "    |_|\\_\\\\___||_||_|\\_\\\\___/ \n" +
            "\n";

    public static final Keiko INSTANCE = new Keiko();

    @Getter
    private volatile LaunchState launchState = LaunchState.NOT_LAUNCHED;

    @Getter
    private BuildProperties buildProperties;

    @Getter
    private File keikoExecutable;

    @Getter
    private File serverDir;

    @Getter
    private File workDir;

    @Getter
    private File pluginsDir;

    @Getter
    private PluginContext pluginContext;

    @Getter
    private final KeikoLogger logger;

    @Getter
    private StaticAnalysisManager staticAnalysisManager;

    @Getter
    private RuntimeProtect runtimeProtect;

    private File proxiedExecutable;

    @SuppressWarnings ("UseOfSystemOutOrSystemErr")
    private Keiko() {
        checkEnvironment();
        fetchBuildProperties();

        System.out.println(LOGO);
        System.out.println("      --  " + buildProperties.getVersion());
        System.out.println("      --  " + buildProperties.getTimestamp());
        System.out.println("\n\n");

        installEverything();

        logger = new KeikoLogger(new File(workDir, "logs"));
        logger.debugLocalized("startup.workDir", workDir.getAbsolutePath());
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            INSTANCE.getLogger().warningLocalized("startup.noArgsErr.line1");
            INSTANCE.getLogger().warningLocalized("startup.noArgsErr.line2");
            INSTANCE.getLogger().warningLocalized("startup.noArgsErr.line3");
            System.exit(1);

            return;
        }

        File proxiedExecutable = new File(String.join(" ", args));

        if (!proxiedExecutable.exists()) {
            INSTANCE.getLogger().warningLocalized("startup.jarErr.notExists");
            System.exit(1);

            return;
        }

        if (!proxiedExecutable.exists()) {
            INSTANCE.getLogger().warningLocalized("startup.jarErr.isDir");
            System.exit(1);

            return;
        }

        if (!proxiedExecutable.canRead()) {
            INSTANCE.getLogger().warningLocalized("startup.jarErr.cantRead");
            System.exit(1);

            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(
                INSTANCE::shutdown, "Keiko Proxy Shutdown Hook"));

        INSTANCE.proxiedExecutable = proxiedExecutable;
        INSTANCE.launch();
    }

    private void checkEnvironment() {
        // Check Java security manager and security policies.
        SecurityManager securityManager = System.getSecurityManager();

        if (securityManager != null)
            throw new IllegalStateException(
                    "Keiko must be ran without a pre-set SecurityManager " +
                    "(detected: " + securityManager.getClass().getName() + ")");

        if (System.getProperty("java.security.manager") != null
                || System.getProperty("java.security.policy") != null)
            throw new IllegalStateException("Keiko must be ran without pre-set Java security policies");

        // Check system class loader.
        String propSysLoader = System.getProperty("java.system.class.loader");

        if (propSysLoader != null)
            throw new IllegalStateException(
                    "Keiko must be launched with the default system class loader " +
                    "(detected: " + propSysLoader + ")");

        ClassLoader sysLoader = Objects.requireNonNull(
                ClassLoader.getSystemClassLoader(), "expected non-null sysLoader");

        ClassLoader clsLoader = Objects.requireNonNull(
                getClass().getClassLoader(), "expected non-null clsLoader");

        if (clsLoader != sysLoader)
            throw new IllegalStateException(
                    "Keiko must be launched with the default system class loader: " +
                    "expected " + sysLoader.getClass().getName() + ", " +
                    "got " + clsLoader.getClass().getName() + " (clsLoader)");

        ClassLoader thrLoader = Objects.requireNonNull(
                Thread.currentThread().getContextClassLoader(), "expected non-null thrLoader");

        if (thrLoader != sysLoader)
            throw new IllegalStateException(
                    "Keiko must be launched with the default system class loader: " +
                    "expected " + sysLoader.getClass().getName() + ", " +
                    "got " + thrLoader.getClass().getName() + " (thrLoader)");
    }

    private void fetchBuildProperties() {
        // Keiko build info (build.properties)
        try (InputStream stream = Thread.currentThread()
                .getContextClassLoader().getResourceAsStream("build.properties")) {
            Properties properties = new Properties();
            properties.load(stream);
            buildProperties = new BuildProperties(properties);
        } catch (IOException | MalformedVersionException | NullPointerException ex) {
            throw new RuntimeException("failed to load build.properties", ex);
        }
    }

    private void installEverything() {
        // Initialize Keiko working directory.
        workDir = new File(KeikoProperties.workDir);
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();
        serverDir = workDir.getAbsoluteFile().getParentFile();

        if (!serverDir.isDirectory())
            throw new RuntimeException(
                    "Keiko JAR must be placed near the original server executable");

        KeikoInstaller installer = new KeikoInstaller(workDir);

        // Create a local copy of Keiko license.
        // (Always do this on startup to prevent accidential modification.)
        File license = new File(workDir, "LICENSE");

        if (license.exists()) {
            if (license.isFile()) {
                if (!license.delete())
                    throw new IllegalStateException("failed to delete the local license file");
            } else
                throw new IllegalStateException("the local license file is a directory");
        }

        installer.checkInstallation(license, license.getName());

        // Install default configuration files, if some are missing.
        ConfigurationLoader.load(installer, GlobalConfig        .class);
        ConfigurationLoader.load(installer, InspectionsConfig   .class);
        ConfigurationLoader.load(installer, RuntimeProtectConfig.class);

        // Initialize static code that depends on some configurations (e.g. locale).
        KeikoLogger.Level.initLocalizedLevelNames();
    }

    private void ensureUnambiguous() {
        // Simple search for other Keiko JARs installed in the same directory.
        // Such installations might be confusing, especially when the user is
        // constantly switching between release and development builds.
        File[] files = keikoExecutable.getParentFile().listFiles();

        if (files == null)
            throw new IllegalStateException("keikoExecutable#parent has no files (null)");

        for (File file : files) {
            if (!file.equals(keikoExecutable)
                    && file.getName().toLowerCase().contains("keiko")
                    && file.getName().endsWith(".jar")) {
                // Two or more Keiko executables found on the same path.
                // This might be confusing for the user. Warn and exit.
                logger.warningLocalized("startup.ambiguousInstallation",
                        keikoExecutable.getName(), file.getName());
                System.exit(1);
            }
        }
    }

    private void launch() {
        if (launchState != LaunchState.NOT_LAUNCHED)
            throw new IllegalStateException("cannot start twice");

        launchState = LaunchState.LAUNCHING;

        findKeikoExecutable();
        ensureUnambiguous();
        checkForUpdates();
        indexPlugins();
        ensurePluginsIntegrity();
        runStaticAnalyses();
        setupRuntimeProtect();
        launchProxy();
    }

    private void shutdown() {
        try {
            logger.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        //noinspection UseOfSystemOutOrSystemErr
        System.out.println("[Keiko] Bye!");
    }

    private void findKeikoExecutable() {
        try {
            keikoExecutable = new File(getClass()
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException ex) {
            throw new RuntimeException("failed to find the Keiko executable", ex);
        }

        if (!keikoExecutable.isFile())
            throw new RuntimeException("the Keiko executable is not a file");

        if (!keikoExecutable.canRead())
            throw new RuntimeException("the Keiko executable cannot be read from");

        if (!keikoExecutable.canWrite())
            throw new RuntimeException("the Keiko executable cannot be written to");
    }

    private void checkForUpdates() {
        int intervalMinutes = GlobalConfig.getUpdaterIntervalMinutes();
        KeikoUpdater updater = new KeikoUpdater(
                buildProperties.getVersion(), GlobalConfig.getUpdaterDownload());

        if (intervalMinutes != -1 /* disable */) {
            updater.run(); // run in this thread immediately
            long millis = TimeUnit.MINUTES.toMillis(intervalMinutes);

            if (intervalMinutes != 0 /* only check at startup */)
                new Timer().schedule(updater, millis, millis);
        }
    }

    private void indexPlugins() {
        pluginsDir = new File("plugins");
        pluginContext = PluginContext.currentContext(pluginsDir);

        if (pluginContext == null) {
            logger.warningLocalized("pluginsIndex.abortingLine1");
            logger.warningLocalized("pluginsIndex.abortingLine2");
            System.exit(1);
        }
    }

    private void ensurePluginsIntegrity() {
        IntegrityChecker checker = new IntegrityChecker();
        boolean abortStartup = checker.run(pluginContext);

        if (abortStartup) {
            // Some plugins' integrity has been violated.
            logger.warningLocalized("integrityChecker.abortingLine1");
            logger.warningLocalized("integrityChecker.abortingLine2");
            System.exit(1);
        }
    }

    private void runStaticAnalyses() {
        // TODO: 22.06.2021 support for other CacheManager implementations (e.g. cloud-based)
        staticAnalysisManager = new StaticAnalysisManager(
                pluginContext, new LocalFileStorageCacheManager());

        double beginTime = System.nanoTime();
        boolean abortStartup;

        abortStartup = staticAnalysisManager.inspectAllPlugins();

        double secondsElapsed = (System.nanoTime() - beginTime) / 10E+9;
        String secondsElapsedRounded = String.format("%.2f", secondsElapsed);
        logger.debugLocalized("staticInspections.timeElapsed", secondsElapsedRounded);

        if (abortStartup) {
            // Failed to inspect some plugin(s).
            logger.warningLocalized("staticInspections.abortingLine1");
            logger.warningLocalized("staticInspections.abortingLine2");
            System.exit(1);

            return;
        }

        abortStartup = staticAnalysisManager.processResults();

        if (abortStartup) {
            // Inspection results of some plugin(s) are critical (server startup must not proceed).
            logger.warningLocalized("staticInspections.abortingLine1");
            logger.warningLocalized("staticInspections.abortingLine2");
            System.exit(1);
        }
    }

    private void setupRuntimeProtect() {
        runtimeProtect = new RuntimeProtect();
        runtimeProtect.setupDomainAccessControl();
    }

    private void launchProxy() {
        logger.infoLocalized("startup.launchingProxy");

        try {
            KeikoClassLoader loader = new KeikoClassLoader(proxiedExecutable);
            Thread.currentThread().setContextClassLoader(loader);
            logger.debugLocalized("startup.classLoaderStats",
                    loader.getLoadResult().successes, loader.getLoadResult().failures);

            logger.debugLocalized("startup.proxyBegin", loader.getBootstrapClassName());
            Class<?> bootstrapClass = loader.findClass(loader.getBootstrapClassName());
            Method bootstrapMethod = bootstrapClass.getMethod("main", String[].class);
            bootstrapMethod.invoke(null, (Object) new String[0]);

            launchState = LaunchState.LAUNCHED_PROXY;
        } catch (Exception ex) {
            logger.warningLocalized("startup.proxyErr");
            logger.error("Failed to launch Keiko proxy", ex);
            System.exit(1);
        }
    }

    public enum LaunchState {
        NOT_LAUNCHED,
        LAUNCHING,
        LAUNCHED_PROXY,
        LAUNCHED_TOOL
    }

}
