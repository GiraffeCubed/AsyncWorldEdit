/*
 * AsyncWorldEdit a performance improvement plugin for Minecraft WorldEdit plugin.
 * Copyright (c) 2014, SBPrime <https://github.com/SBPrime/>
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
package org.primesoft.asyncworldedit.core;

import org.primesoft.asyncworldedit.api.inner.IChunkWatch;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.primesoft.asyncworldedit.api.taskdispatcher.ITaskDispatcher;
import org.primesoft.asyncworldedit.utils.InOutParam;

/**
 * This class suppresses chunk unloading
 *
 * @author SBPrime
 */
public abstract class ChunkWatch implements IChunkWatch {
    private final static Object INSTANCE = new Object();
   
    private final Map<String, WorldEntry> m_entries = new ConcurrentHashMap<>();

    /**
     * The dispatcher
     */
    private ITaskDispatcher m_dispatcher = null;

    protected long encode(int x, int z) {
        return (long) x << 32 | z & 0xFFFFFFFFL;
    }
    
    private WorldEntry getEntry(String world) {
        return m_entries.computeIfAbsent(world, _wn -> new WorldEntry());
    }

    /**
     * Remove all chunk unload queues
     */
    @Override
    public void clear() {
        m_entries.values().forEach(i -> i.Watched.clear());        
    }        

    /**
     * Add chunk to suppress chunk unload queue
     *
     * @param cx
     * @param cz
     * @param worldName
     */
    @Override
    public void add(int cx, int cz, String worldName) {        
        final long chunk = encode(cx, cz);
        final WorldEntry worldEntry = getEntry(worldName);
        worldEntry.Watched.compute(chunk, (_chunk, value) -> {
            if (value == null) {
                forceloadOn(worldName, cx, cz);
                return 1;
            }

            return value + 1;
        });
    }

    /**
     * Remove chunk from suppress chunk unload queue
     *
     * @param cx
     * @param cz
     * @param worldName
     */
    @Override
    public void remove(int cx, int cz, String worldName) {
        final WorldEntry worldEntry = getEntry(worldName);
        final long chunk = encode(cx, cz);
        
        worldEntry.Watched.computeIfPresent(chunk, (_chunk, value) -> {            
            if (value == null) {
                return null;
            }

            value = value - 1;
            try {
                return value <= 0 ? null : value;
            } finally {
                if (value <= 0) {
                    forceloadOff(worldName, cx, cz);
                }
            }
        });        
    }

    protected final int getReferences(String worldName, int cx, int cz) {
        final WorldEntry worldEntry = getEntry(worldName);
        final long chunk = encode(cx, cz);
                
        Integer value = worldEntry.Watched.get(chunk);
        if (value == null) {
            return 0;
        }
        return value;
    }

    protected void chunkLoaded(String worldName, int cx, int cz) {
        getEntry(worldName).Loaded.put(encode(cx, cz), INSTANCE);
    }

    public boolean chunkUnloading(String worldName, int cx, int cz) {
        final WorldEntry worldEntry = getEntry(worldName);
        final long chunk = encode(cx, cz);
        final InOutParam<Boolean> result = InOutParam.Ref(false);
        
        worldEntry.Watched.computeIfPresent(chunk, (_chunk, value) -> {
            boolean cancel = value != null && value > 0;
            
            if (cancel && supportUnloadCancel()) {
                result.setValue(true);
            } else {
                worldEntry.Loaded.remove(chunk);
            }

            return value;
        });

        return result.getValue();
    }

    /**
     * Set chunk data as unloaded
     *
     * @param cx
     * @param cz
     * @param worldName
     */
    @Override
    public void setChunkUnloaded(int cx, int cz, String worldName) {
        getEntry(worldName).Loaded.remove(encode(cx, cz));
    }

    /**
     * Set chunk data as unloaded
     *
     * @param cx
     * @param cz
     * @param worldName
     */
    @Override
    public void setChunkLoaded(int cx, int cz, String worldName) {
        chunkLoaded(worldName, cx, cz);
    }

    @Override
    public boolean isChunkLoaded(int cx, int cz, String worldName) {
        return getEntry(worldName).Loaded.containsKey(encode(cx, cz));
    }

    @Override
    public void loadChunk(final int cx, final int cz, final String worldName) {
        add(cx, cz, worldName);
        try {
            if (isChunkLoaded(cx, cz, worldName) || m_dispatcher == null) {
                return;
            }

            m_dispatcher.queueFastOperation(() -> doLoadChunk(cx, cz, worldName));
        } finally {
            remove(cx, cz, worldName);
        }
    }

    /**
     * Register the chunk watcher events
     */
    public abstract void registerEvents();

    /**
     * Do the actual chunk loading
     *
     * @param cx
     * @param cz
     * @param worldName
     * @return
     */
    protected abstract boolean doLoadChunk(int cx, int cz, String worldName);

    @Override
    public void setTaskDispat(ITaskDispatcher dispatcher) {
        m_dispatcher = dispatcher;
    }

    protected abstract void forceloadOff(String world, int cx, int cz);

    protected abstract void forceloadOn(String world, int cx, int cz);

    protected abstract boolean supportUnloadCancel();
    
    private static class WorldEntry {
        /**
         * List of all loaded chunks
         */
        public final Map<Long, Object> Loaded = new ConcurrentHashMap<>();
    
        /**
         * Suppressed chunks
         */
        public final Map<Long, Integer> Watched = new ConcurrentHashMap<>();
    }
}
