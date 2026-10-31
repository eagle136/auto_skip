package com.v5yn133n3.skp.engine

import android.util.Log

/**
 * 最近一轮扫带任务的开销，日志里一行、主页上显示同一行。
 *
 * 分阶段记（截图 / 找框 / 模型），因为这三段的优化手段完全不同：
 * 截图是 `screencap` 的物理开销，找框是纯 CPU（含压半分辨率），模型是三层卷积。
 */
object CornerStats {

    @Volatile
    var line: String = "扫带：还没跑过"
        private set

    /**
     * 任务运行中的实时行。任务现在要跟到 10 分钟（或点完后 15s 静默），只在结束时
     * report 的话主页会十分钟不动，看着像没在工作。每帧刷一次这一行，零额外开销。
     */
    fun live(pkg: String, frames: Int, tiles: Int, capMs: Long, scanMs: Long, modelMs: Long, calls: Int) {
        if (frames <= 0) return
        val f = frames
        line = "扫带(进行中)：${frames}帧 ${tiles / f}块/帧 截图${capMs / f}ms/帧 " +
            "找框${scanMs / f}ms/帧 " +
            "模型${if (calls > 0) modelMs / calls else 0}ms/次×$calls"
    }

    fun report(
        pkg: String,
        frames: Int,
        capMs: Long,
        scanMs: Long,
        modelMs: Long,
        modelCalls: Int,
        maxFrameMs: Long,
        totalMs: Long,
        hit: Boolean,
        reason: String,
        tiles: Int = 0,
    ) {
        val f = frames.coerceAtLeast(1)
        val text = "扫带：${frames}帧 ${tiles / f}块/帧 截图${capMs / f}ms/帧 " +
            "找框${scanMs / f}ms/帧 " +
            "模型${if (modelCalls > 0) (modelMs / modelCalls).toString() else "-"}ms/次×$modelCalls " +
            "最慢${maxFrameMs}ms 共${totalMs}ms ${if (hit) "命中" else "未中"}"
        line = text
        Log.i(
            SkipService.TAG,
            "cost kind=CORNER pkg=$pkg frames=$frames tiles=$tiles cap=${capMs / f}ms " +
                "scan=${scanMs / f}ms model=${if (modelCalls > 0) modelMs / modelCalls else 0}ms " +
                "calls=$modelCalls maxFrame=${maxFrameMs}ms total=${totalMs}ms hit=$hit reason=$reason",
        )
    }
}
