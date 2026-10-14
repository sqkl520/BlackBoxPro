package com.blackboxpro.fabric.action.debug

import com.blackboxpro.fabric.action.ActionExecutor
import com.blackboxpro.fabric.action.ActionResult
import com.google.gson.JsonObject

/**
 * Action ID: "debug_sample_subscription"
 *
 * ⚠️ Minecraft 1.21.4 不存在 debug 订阅系统：
 * 无 `net.minecraft.world.debug` 包，也没有 `DebugSubscriptionRequestC2SPacket`
 * （该体系为 1.21.11 新增）。故本 Action 优雅降级——
 * 保留类与注册（保证 ActionRegistry 完整），execute 直接返回失败并说明原因。
 */
class DebugSampleAction : ActionExecutor {

    override fun execute(params: JsonObject): ActionResult =
        ActionResult.fail("debug_sample is not supported on Minecraft 1.21.4 (no debug subscription system)")

    override fun execute(params: JsonObject, commandId: String): ActionResult = execute(params)
}
