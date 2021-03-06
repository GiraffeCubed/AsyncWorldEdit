/*
 * AsyncWorldEdit a performance improvement plugin for Minecraft WorldEdit plugin.
 * Copyright (c) 2019, SBPrime <https://github.com/SBPrime/>
 * Copyright (c) AsyncWorldEdit contributors
 *
 * All rights reserved.
 *
 * Redistribution in source, use in source and binary forms, with or without
 * modification, are permitted free of charge provided that the following 
 * conditions are met:
 *
 * 1.  Redistributions of source code must retain the above copyright notice, this
 *     list of conditions and the following disclaimer.
 * 2.  Redistributions of source code, with or without modification, in any form
 *     other then free of charge is not allowed,
 * 3.  Redistributions of source code, with tools and/or scripts used to build the 
 *     software is not allowed,
 * 4.  Redistributions of source code, with information on how to compile the software
 *     is not allowed,
 * 5.  Providing information of any sort (excluding information from the software page)
 *     on how to compile the software is not allowed,
 * 6.  You are allowed to build the software for your personal use,
 * 7.  You are allowed to build the software using a non public build server,
 * 8.  Redistributions in binary form in not allowed.
 * 9.  The original author is allowed to redistrubute the software in bnary form.
 * 10. Any derived work based on or containing parts of this software must reproduce
 *     the above copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided with the
 *     derived work.
 * 11. The original author of the software is allowed to change the license
 *     terms or the entire license of the software as he sees fit.
 * 12. The original author of the software is allowed to sublicense the software
 *     or its parts using any license terms he sees fit.
 * 13. By contributing to this project you agree that your contribution falls under this
 *     license.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.primesoft.asyncworldedit.utils;

import java.lang.reflect.Field;
import java.util.List;
import org.bukkit.plugin.java.JavaPluginLoader;

/**
 *
 * @author SBPrime
 */
public final class ClassLoaderHelper {

    private final static String PLUGIN_CLASS_LOADER = "org.bukkit.plugin.java.PluginClassLoader";

    private static final Class<?> s_clsPluginClassLoader;
    
    private static final Field s_fieldLoader;
    private static final Field s_fieldLoaders;

    static {
        try {
            s_clsPluginClassLoader = ClassLoaderHelper.class.getClassLoader().loadClass(PLUGIN_CLASS_LOADER);
            s_fieldLoader = s_clsPluginClassLoader.getDeclaredField("loader");
            s_fieldLoader.setAccessible(true);
            
            s_fieldLoaders = JavaPluginLoader.class.getDeclaredField("loaders");
            s_fieldLoaders.setAccessible(true);
        } catch (Throwable ex) {
            throw new IllegalStateException("Unable to initialize. Unable to get the PluginClassLoader.", ex);
        }
    }

    private ClassLoaderHelper() {
    }

    
    public static Class<?> getPluginClassLoader() {
        return s_clsPluginClassLoader;
    }
    
    /**
     * Tries to get the plugin class loader for the provided class
     *
     * @param cls
     * @return
     */
    public static ClassLoader getPluginClassLoader(Class<?> cls) {
        ClassLoader cl = cls.getClassLoader();
        while (cl != null && !s_clsPluginClassLoader.isInstance(cl)) {
            cl = cl.getParent();
        }

        return cl;
    }
    
    private static JavaPluginLoader getPluginLoader(ClassLoader cl) {
        try {
            return (JavaPluginLoader) s_fieldLoader.get(cl);
        } catch (Throwable ex) {
            throw new IllegalStateException("Unable to get plugin loader.", ex);
        } 
    }
    
    private static List getLoaders(JavaPluginLoader jpl) {
        try {
            return (List) s_fieldLoaders.get(jpl);
        } catch (Throwable ex) {
            throw new IllegalStateException("Unable to get loaders.", ex);
        } 
    }
    
    public static void addLoader(ClassLoader classLoaderPlugin) {
        getLoaders(getPluginLoader(classLoaderPlugin)).add(classLoaderPlugin);
    }

    public static void removeLoader(ClassLoader classLoaderPlugin) {
        getLoaders(getPluginLoader(classLoaderPlugin)).remove(classLoaderPlugin);        
    }

}
