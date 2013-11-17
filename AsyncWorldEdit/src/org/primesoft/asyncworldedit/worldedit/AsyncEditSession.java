/*
 * The MIT License
 *
 * Copyright 2013 SBPrime.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.primesoft.asyncworldedit.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalWorld;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.bags.BlockBag;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.expression.ExpressionException;
import com.sk89q.worldedit.masks.Mask;
import com.sk89q.worldedit.patterns.Pattern;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.TreeGenerator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.primesoft.asyncworldedit.BlocksHubIntegration;
import org.primesoft.asyncworldedit.ConfigProvider;
import org.primesoft.asyncworldedit.PluginMain;
import org.primesoft.asyncworldedit.blockPlacer.BlockPlacer;
import org.primesoft.asyncworldedit.blockPlacer.BlockPlacerBlockEntry;
import org.primesoft.asyncworldedit.blockPlacer.BlockPlacerJobEntry;
import org.primesoft.asyncworldedit.blockPlacer.BlockPlacerMaskEntry;

/**
 *
 * @author SBPrime
 */
public class AsyncEditSession extends EditSession {

    /**
     * Player
     */
    private final String m_player;
    /**
     * Async block placer
     */
    private final BlockPlacer m_blockPlacer;
    /**
     * Current craftbukkit world
     */
    private final World m_world;
    /**
     * The blocks hub integrator
     */
    private final BlocksHubIntegration m_bh;
    /**
     * The parent factory class
     */
    private final AsyncEditSessionFactory m_factory;
    /**
     * Force all functions to by performed in async mode this is used to
     * override the config by API calls
     */
    private boolean m_asyncForced;
    /**
     * Indicates that the async mode has been disabled (inner state)
     */
    private boolean m_asyncDisabled;
    /**
     * Plugin instance
     */
    private final PluginMain m_plugin;
    /**
     * Bukkit schedule
     */
    private final BukkitScheduler m_schedule;
    /**
     * Number of async tasks
     */
    private final HashSet<BlockPlacerJobEntry> m_asyncTasks;

    public String getPlayer() {
        return m_player;
    }

    public AsyncEditSession(AsyncEditSessionFactory factory, PluginMain plugin,
            String player, LocalWorld world, int maxBlocks) {
        super(world, maxBlocks);
        m_asyncTasks = new HashSet<BlockPlacerJobEntry>();
        m_plugin = plugin;
        m_bh = plugin.getBlocksHub();
        m_factory = factory;
        m_player = player;
        m_blockPlacer = plugin.getBlockPlacer();
        m_schedule = plugin.getServer().getScheduler();
        if (world != null) {
            m_world = plugin.getServer().getWorld(world.getName());
        } else {
            m_world = null;
        }
        m_asyncForced = false;
        m_asyncDisabled = false;
    }

    public AsyncEditSession(AsyncEditSessionFactory factory, PluginMain plugin,
            String player, LocalWorld world, int maxBlocks,
            BlockBag blockBag) {
        super(world, maxBlocks, blockBag);
        m_asyncTasks = new HashSet<BlockPlacerJobEntry>();
        m_plugin = plugin;
        m_bh = plugin.getBlocksHub();
        m_factory = factory;
        m_player = player;
        m_blockPlacer = plugin.getBlockPlacer();
        m_schedule = plugin.getServer().getScheduler();
        if (world != null) {
            m_world = plugin.getServer().getWorld(world.getName());
        } else {
            m_world = null;
        }
        m_asyncForced = false;
        m_asyncDisabled = false;
    }

    @Override
    public boolean rawSetBlock(Vector pt, BaseBlock block) {
        return this.rawSetBlock(pt, -1, block);
    }

    public boolean rawSetBlock(Vector pt, int jobId, BaseBlock block) {
        if (!m_bh.canPlace(m_player, m_world, pt)) {
            return false;
        }

        if (m_asyncForced || (PluginMain.hasAsyncMode(m_player) && !m_asyncDisabled)) {
            return m_blockPlacer.addTasks(new BlockPlacerBlockEntry(this, jobId, pt, block));
        } else {
            return doRawSetBlock(pt, block);
        }
    }

    @Override
    public void setMask(Mask mask) {
        if (m_asyncForced || (PluginMain.hasAsyncMode(m_player) && !m_asyncDisabled)) {
            m_blockPlacer.addTasks(new BlockPlacerMaskEntry(this, -1, mask));
        } else {
            doSetMask(mask);
        }
    }

    @Override
    public void flushQueue() {
        boolean queued = isQueueEnabled();
        super.flushQueue();
        if (queued) {
            resetAsync();
        }
    }

    @Override
    public void undo(EditSession sess) {
        //TODO: Make async stage 1
        synchronized (m_asyncTasks) {
            for (BlockPlacerJobEntry job : m_asyncTasks) {
                job.cancel();
            }
        }
        checkAsync(WorldeditOperations.undo);
        UndoSession undoSession = new UndoSession();
        super.undo(undoSession);

        final Map.Entry<Vector, BaseBlock>[] blocks = undoSession.getEntries();
        final HashMap<Integer, HashMap<Integer, HashSet<Integer>>> placedBlocks = new HashMap<Integer, HashMap<Integer, HashSet<Integer>>>();

        for (int i = blocks.length - 1; i >= 0; i--) {
            Map.Entry<Vector, BaseBlock> entry = blocks[i];
            Vector pos = entry.getKey();
            BaseBlock block = entry.getValue();

            int x = pos.getBlockX();
            int y = pos.getBlockY();
            int z = pos.getBlockZ();
            boolean ignore = false;

            HashMap<Integer, HashSet<Integer>> mapX = placedBlocks.get(x);
            if (mapX == null) {
                mapX = new HashMap<Integer, HashSet<Integer>>();
                placedBlocks.put(x, mapX);
            }

            HashSet<Integer> mapY = mapX.get(y);
            if (mapY == null) {
                mapY = new HashSet<Integer>();
                mapX.put(y, mapY);
            }
            if (mapY.contains(z)) {
                ignore = true;
            } else {
                mapY.add(z);
            }

            if (!ignore) {
                sess.smartSetBlock(pos, block);
            }
        }

        sess.flushQueue();
        if (!isQueueEnabled()) {
            resetAsync();
        }
    }

    @Override
    public void redo(EditSession sess) {
        //TODO: Make async stage 1
        checkAsync(WorldeditOperations.redo);
        super.redo(sess);
        if (!isQueueEnabled()) {
            resetAsync();
        }
    }

    @Override
    public int fillXZ(final Vector origin, final BaseBlock block, final double radius, final int depth,
            final boolean recursive)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.fillXZ);
        if (!isAsync) {
            return super.fillXZ(origin, block, radius, depth, recursive);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "fillXZ");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "fillXZ",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.fillXZ(origin, block, radius, depth, recursive);
            }
        });

        return 0;
    }

    @Override
    public int fillXZ(final Vector origin, final Pattern pattern, final double radius, final int depth,
            final boolean recursive)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.fillXZ);
        if (!isAsync) {
            return super.fillXZ(origin, pattern, radius, depth, recursive);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "fillXZ");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "fillXZ",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.fillXZ(origin, pattern, radius, depth, recursive);
            }
        });

        return 0;
    }

    @Override
    public int removeAbove(final Vector pos, final int size, final int height)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.removeAbove);
        if (!isAsync) {
            return super.removeAbove(pos, size, height);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "removeAbove");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "removeAbove",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.removeAbove(pos, size, height);
            }
        });

        return 0;
    }

    @Override
    public int removeBelow(final Vector pos, final int size, final int height)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.removeBelow);
        if (!isAsync) {
            return super.removeBelow(pos, size, height);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "removeBelow");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "removeBelow",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.removeBelow(pos, size, height);
            }
        });

        return 0;
    }

    @Override
    public int removeNear(final Vector pos, final int blockType, final int size)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.removeNear);
        if (!isAsync) {
            return super.removeNear(pos, blockType, size);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "removeNear");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "removeNear",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.removeNear(pos, blockType, size);
            }
        });

        return 0;
    }

    @Override
    public int setBlocks(final Region region, final BaseBlock block)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.setBlocks);

        if (!isAsync) {
            return super.setBlocks(region, block);
        }


        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "setBlocks");

        m_blockPlacer.addJob(m_player, job);
        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "setBlocks",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.setBlocks(region, block);
            }
        });

        return 0;
    }

    @Override
    public int setBlocks(final Region region, final Pattern pattern)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.setBlocks);

        if (!isAsync) {
            return super.setBlocks(region, pattern);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "setBlocks");
        m_blockPlacer.addJob(m_player, job);

        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "setBlocks",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.setBlocks(region, pattern);
            }
        });
        return 0;
    }

    @Override
    public int replaceBlocks(final Region region,
            final Set<BaseBlock> fromBlockTypes,
            final BaseBlock toBlock)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.replaceBlocks);

        if (!isAsync) {
            return super.replaceBlocks(region, fromBlockTypes, toBlock);
        }


        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "replaceBlocks");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "replaceBlocks",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.replaceBlocks(region, fromBlockTypes, toBlock);
            }
        });
        return 0;
    }

    @Override
    public int replaceBlocks(final Region region,
            final Set<BaseBlock> fromBlockTypes,
            final Pattern pattern)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.replaceBlocks);
        if (!isAsync) {
            return super.replaceBlocks(region, fromBlockTypes, pattern);
        }


        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "replaceBlocks");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "replaceBlocks",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.replaceBlocks(region, fromBlockTypes, pattern);
            }
        });
        return 0;
    }

    @Override
    public int makeCuboidFaces(final Region region, final BaseBlock block)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.makeCuboidFaces);
        if (!isAsync) {
            return super.makeCuboidFaces(region, block);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "makeCuboidFaces");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "makeCuboidFaces",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.makeCuboidFaces(region, block);
            }
        });

        return 0;
    }

    @Override
    public int makeCuboidFaces(final Region region, final Pattern pattern)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.makeCuboidFaces);
        if (!isAsync) {
            return super.makeCuboidFaces(region, pattern);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "makeCuboidFaces");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "makeCuboidFaces",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.makeCuboidFaces(region, pattern);
            }
        });

        return 0;
    }

    @Override
    public int makeCuboidWalls(final Region region, final BaseBlock block)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.makeCuboidWalls);
        if (!isAsync) {
            return super.makeCuboidWalls(region, block);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "makeCuboidWalls");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "makeCuboidWalls",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.makeCuboidWalls(region, block);
            }
        });

        return 0;
    }

    @Override
    public int makeCuboidWalls(final Region region, final Pattern pattern)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.makeCuboidWalls);
        if (!isAsync) {
            return super.makeCuboidWalls(region, pattern);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "makeCuboidWalls");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "makeCuboidWalls",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.makeCuboidWalls(region, pattern);
            }
        });

        return 0;
    }

    @Override
    public int overlayCuboidBlocks(final Region region, final BaseBlock block)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.overlayCuboidBlocks);
        if (!isAsync) {
            return super.overlayCuboidBlocks(region, block);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "overlayCuboidBlocks");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "overlayCuboidBlocks",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.overlayCuboidBlocks(region, block);
            }
        });

        return 0;
    }

    @Override
    public int overlayCuboidBlocks(final Region region, final Pattern pattern)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.overlayCuboidBlocks);
        if (!isAsync) {
            return super.overlayCuboidBlocks(region, pattern);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "overlayCuboidBlocks");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "overlayCuboidBlocks",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.overlayCuboidBlocks(region, pattern);
            }
        });

        return 0;
    }

    @Override
    public int naturalizeCuboidBlocks(final Region region)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.naturalizeCuboidBlocks);
        if (!isAsync) {
            return super.naturalizeCuboidBlocks(region);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "naturalizeCuboidBlocks");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "naturalizeCuboidBlocks",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.naturalizeCuboidBlocks(region);
            }
        });

        return 0;
    }

    @Override
    public int stackCuboidRegion(final Region region, final Vector dir, final int count,
            final boolean copyAir)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.stackCuboidRegion);
        if (!isAsync) {
            return super.stackCuboidRegion(region, dir, count, copyAir);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "stackCuboidRegion");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "stackCuboidRegion",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.stackCuboidRegion(region, dir, count, copyAir);
            }
        });

        return 0;
    }

    @Override
    public int moveCuboidRegion(final Region region, final Vector dir,
            final int distance,
            final boolean copyAir, final BaseBlock replace)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.moveCuboidRegion);
        if (!isAsync) {
            return super.moveCuboidRegion(region, dir, distance, copyAir, replace);
        }


        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "moveCuboidRegion");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "moveCuboidRegion",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.moveCuboidRegion(region, dir, distance, copyAir, replace);
            }
        });
        return 0;
    }

    @Override
    public int drainArea(final Vector pos, final double radius)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.drainArea);
        if (!isAsync) {
            return super.drainArea(pos, radius);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "drainArea");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "drainArea",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.drainArea(pos, radius);
            }
        });

        return 0;
    }

    @Override
    public int fixLiquid(final Vector pos, final double radius, final int moving, final int stationary)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.fixLiquid);
        if (!isAsync) {
            return super.fixLiquid(pos, radius, moving, stationary);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "fixLiquid");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "fixLiquid",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.fixLiquid(pos, radius, moving, stationary);
            }
        });

        return 0;
    }

    @Override
    public int makeCylinder(final Vector pos, final Pattern block, final double radius, final int height,
            final boolean filled)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.makeCylinder);
        if (!isAsync) {
            return super.makeCylinder(pos, block, radius, height, filled);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "makeCylinder");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "makeCylinder",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.makeCylinder(pos, block, radius, height, filled);
            }
        });

        return 0;
    }

    @Override
    public int makeCylinder(final Vector pos, final Pattern block, final double radiusX,
            final double radiusZ, final int height, final boolean filled)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.makeCylinder);
        if (!isAsync) {
            return super.makeCylinder(pos, block, radiusX, radiusZ, height, filled);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "makeCylinder");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "makeCylinder",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.makeCylinder(pos, block, radiusX, radiusZ, height, filled);
            }
        });

        return 0;
    }

    @Override
    public int makeSphere(final Vector pos, final Pattern block, final double radius,
            final boolean filled)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.makeSphere);
        if (!isAsync) {
            return super.makeSphere(pos, block, radius, filled);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "makeSphere");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "makeSphere",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.makeSphere(pos, block, radius, filled);
            }
        });

        return 0;
    }

    @Override
    public int makeSphere(final Vector pos, final Pattern block, final double radiusX,
            final double radiusY, final double radiusZ, final boolean filled)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.makeSphere);
        if (!isAsync) {
            return super.makeSphere(pos, block, radiusX, radiusY, radiusZ, filled);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "makeSphere");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "makeSphere",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.makeSphere(pos, block, radiusX, radiusY, radiusZ, filled);
            }
        });

        return 0;
    }

    @Override
    public int makePyramid(final Vector pos, final Pattern block, final int size, final boolean filled)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.makePyramid);
        if (!isAsync) {
            return super.makePyramid(pos, block, size, filled);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "makePyramid");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "makePyramid",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.makePyramid(pos, block, size, filled);
            }
        });

        return 0;
    }

    @Override
    public int thaw(final Vector pos, final double radius)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.thaw);
        if (!isAsync) {
            return super.thaw(pos, radius);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "thaw");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "thaw",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.thaw(pos, radius);
            }
        });

        return 0;
    }

    @Override
    public int simulateSnow(final Vector pos, final double radius)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.simulateSnow);
        if (!isAsync) {
            return super.simulateSnow(pos, radius);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "simulateSnow");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "simulateSnow",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.simulateSnow(pos, radius);
            }
        });

        return 0;
    }

    @Override
    public int green(final Vector pos, final double radius)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.green);
        if (!isAsync) {
            return super.green(pos, radius);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "green");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "green",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.green(pos, radius);
            }
        });

        return 0;
    }

    @Override
    public int makePumpkinPatches(final Vector basePos, final int size)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.makePumpkinPatches);
        if (!isAsync) {
            return super.makePumpkinPatches(basePos, size);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "makePumpkinPatches");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "makePumpkinPatches",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.makePumpkinPatches(basePos, size);
            }
        });

        return 0;
    }

    @Override
    public int makeForest(final Vector basePos, final int size, final double density,
            final TreeGenerator treeGenerator)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.makeForest);
        if (!isAsync) {
            return super.makeForest(basePos, size, density, treeGenerator);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "makeForest");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "makeForest",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.makeForest(basePos, size, density, treeGenerator);
            }
        });

        return 0;
    }

    @Override
    public int makeShape(final Region region, final Vector zero, final Vector unit,
            final Pattern pattern, final String expressionString,
            final boolean hollow)
            throws ExpressionException, MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.makeShape);
        if (!isAsync) {
            return super.makeShape(region, zero, unit, pattern, expressionString, hollow);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "makeShape");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "makeShape",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                try {
                    return session.makeShape(region, zero, unit, pattern, expressionString, hollow);
                } catch (ExpressionException ex) {
                    Logger.getLogger(AsyncEditSession.class.getName()).log(Level.SEVERE, null, ex);
                    return 0;
                }
            }
        });

        return 0;
    }

    @Override
    public int deformRegion(final Region region, final Vector zero, final Vector unit,
            final String expressionString)
            throws ExpressionException, MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.deformRegion);
        if (!isAsync) {
            return super.deformRegion(region, zero, unit, expressionString);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "deformRegion");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "deformRegion",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                try {
                    return session.deformRegion(region, zero, unit, expressionString);
                } catch (ExpressionException ex) {
                    Logger.getLogger(AsyncEditSession.class.getName()).log(Level.SEVERE, null, ex);
                    return 0;
                }
            }
        });

        return 0;
    }

    @Override
    public int hollowOutRegion(final Region region, final int thickness, final Pattern pattern)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.hollowOutRegion);
        if (!isAsync) {
            return super.hollowOutRegion(region, thickness, pattern);
        }

        final int jobId = getJobId();
        final CancelabeEditSession session = new CancelabeEditSession(this, jobId);
        final BlockPlacerJobEntry job = new BlockPlacerJobEntry(this, session, jobId, "hollowOutRegion");
        m_blockPlacer.addJob(m_player, job);


        m_schedule.runTaskAsynchronously(m_plugin, new AsyncTask(session, m_player, "hollowOutRegion",
                m_blockPlacer, job) {

            @Override
            public int task(CancelabeEditSession session)
                    throws MaxChangedBlocksException {
                return session.hollowOutRegion(region, thickness, pattern);
            }
        });

        return 0;
    }

    /**
     * Add async job
     *
     * @param job
     */
    public void addAsync(BlockPlacerJobEntry job) {
        synchronized (m_asyncTasks) {
            m_asyncTasks.add(job);
        }
    }

    /**
     * Remov async job (done or canceled)
     *
     * @param job
     */
    public void removeAsync(BlockPlacerJobEntry job) {
        synchronized (m_asyncTasks) {
            m_asyncTasks.remove(job);
        }
    }

    @Override
    public int size() {
        final int result = super.size();
        synchronized (m_asyncTasks) {
            if (result <= 0 && m_asyncTasks.size() > 0) {
                return 1;
            }
        }
        return result;
    }

    public boolean doRawSetBlock(Vector location, BaseBlock block) {
        String player = getPlayer();
        World w = getCBWorld();
        BaseBlock oldBlock = getBlock(location);

        boolean success = super.rawSetBlock(location, block);

        if (success && w != null) {
            m_bh.logBlock(player, w, location, oldBlock, block);
        }
        return success;
    }

    public void doSetMask(Mask mask) {
        super.setMask(mask);

    }

    public World getCBWorld() {
        return m_world;
    }

    /**
     * Enables or disables the async mode configuration bypass this function
     * should by used only by other plugins
     *
     * @param value true to enable async mode force
     */
    public void setAsyncForced(boolean value) {
        m_asyncForced = value;
    }

    /**
     * Check if async mode is forced
     *
     * @return
     */
    public boolean isAsyncForced() {
        return m_asyncForced;
    }

    /**
     * This function checks if async mode is enabled for specific command
     *
     * @param operation
     */
    private boolean checkAsync(WorldeditOperations operation) {
        boolean result = m_asyncForced || (ConfigProvider.isAsyncAllowed(operation)
                && PluginMain.hasAsyncMode(m_player));

        m_asyncDisabled = !result;
        return result;
    }

    /**
     * Reset async disabled inner state (enable async mode)
     */
    public void resetAsync() {
        m_asyncDisabled = false;
    }

    /**
     * Get next job id for current player
     *
     * @return Job id
     */
    private int getJobId() {
        return m_blockPlacer.getJobId(m_player);
    }
}
