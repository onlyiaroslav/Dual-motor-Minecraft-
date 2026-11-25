package dev.starling.bridge;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Classloader headless condiviso: blocca il caricamento di classi di
 * rendering/GUI PRIMA della delegazione al parent. Il motore Rust gestisce
 * tutto il rendering; la JVM esegue solo logica di gioco.
 */
public final class HeadlessClassLoader extends URLClassLoader {

    /** Prefissi di classe vietati nell'istanza headless. */
    public static final String[] BLOCKED_PREFIXES = {
            "org.lwjgl.",
            "java.awt.",
            "javax.swing.",
            "javax.imageio.",
            "com.mojang.blaze3d.",
            "net.minecraft.client.renderer.",
            "net.minecraft.client.gui.",
    };

    public HeadlessClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (String prefix : BLOCKED_PREFIXES) {
            if (name.startsWith(prefix)) {
                throw new ClassNotFoundException(
                        "HEADLESS: classe di rendering/GUI bloccata: " + name);
            }
        }
        return super.loadClass(name, resolve);
    }
}