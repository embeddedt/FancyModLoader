/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.fml.loading;

import com.mojang.logging.LogUtils;
import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.LamdbaExceptionUtils;
import cpw.mods.modlauncher.api.NamedPath;
import cpw.mods.modlauncher.serviceapi.ITransformerDiscoveryService;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.zip.ZipFile;
import java.util.Set;

public class ModDirTransformerDiscoverer implements ITransformerDiscoveryService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> SERVICES = Set.of(
        "cpw.mods.modlauncher.api.ITransformationService",
        "net.minecraftforge.forgespi.locating.IModLocator",
        "net.minecraftforge.forgespi.locating.IDependencyLocator"
    );
    private UncheckedIOException alreadyFailed;

    @Override
    public List<NamedPath> candidates(final Path gameDirectory, final String launchTarget) {
        try {
            FMLPaths.loadAbsolutePaths(gameDirectory);
            FMLConfig.load();
            return candidates(gameDirectory);
        } catch (UncheckedIOException e) {
            // we capture any error here and then return an empty list so we can
            // show an error in earlyInitialization which fires next
            this.alreadyFailed = e;
            return List.of();
        }
    }

    @Override
    public void earlyInitialization(final String launchTarget, final String[] arguments) {
        ImmediateWindowHandler.load(launchTarget, arguments);
        if (this.alreadyFailed!=null) {
            String errorCause;
            if (this.alreadyFailed.getCause() instanceof FileAlreadyExistsException faee) {
                errorCause = "File already exists: " + faee.getFile() + "\nYou need to move this out of the way, so we can put a directory there.";
            } else if (this.alreadyFailed.getCause() instanceof AccessDeniedException ade) {
                errorCause = "Access denied trying to create a file or directory "+ade.getMessage() +"\nThe game directory is probably read-only. Check the write permission on it.";
            } else {
                errorCause = "An unexpected IO error occurred trying to setup the game directory\n"+this.alreadyFailed.getCause().getMessage();
            }
            ImmediateWindowHandler.crash(errorCause);
            throw this.alreadyFailed;
        }
    }

    @Override
    public List<NamedPath> candidates(final Path gameDirectory) {
        ModDirTransformerDiscoverer.scan(gameDirectory);
        return List.copyOf(found);
    }

    private final static List<NamedPath> found = new ArrayList<>();

    public static List<Path> allExcluded() {
        return found.stream().map(np->np.paths()[0]).toList();
    }

    private static void scan(final Path gameDirectory) {
        final Path modsDir = gameDirectory.resolve(FMLPaths.MODSDIR.relative()).toAbsolutePath().normalize();
        if (!Files.exists(modsDir)) {
            // Skip if the mods dir doesn't exist yet.
            return;
        }
        try (var walk = Files.walk(modsDir, 1)){
            walk.forEach(ModDirTransformerDiscoverer::visitFile);
        } catch (IOException | IllegalStateException ioe) {
            LOGGER.error("Error during early discovery", ioe);
        }
    }

    private static void visitFile(Path path) {
        if (!Files.isRegularFile(path)) return;
        if (!path.toString().endsWith(".jar")) return;
        if (LamdbaExceptionUtils.uncheck(() -> Files.size(path)) == 0) return;

        SecureJar jar = SecureJar.from(path);
        jar.moduleDataProvider().descriptor().provides().stream()
            .map(ModuleDescriptor.Provides::service)
            .filter(SERVICES::contains)
            .forEach(s -> found.add(new NamedPath(s, path)));
    }
}
