package com.blackboxpro.fabric.action.client

import com.blackboxpro.common.runtime.action.ActionResult
import com.blackboxpro.common.runtime.config.RuntimeBlackBoxConfig
import com.blackboxpro.common.runtime.dispatcher.RuntimeResponseSender
import com.blackboxpro.common.runtime.scheduler.RuntimeTickScheduler
import com.blackboxpro.common.runtime.screenshot.RuntimeScreenshotBridge
import com.blackboxpro.fabric.action.ActionExecutor
import com.blackboxpro.fabric.util.ContainerTooltipHelper
import com.blackboxpro.fabric.util.requireInt
import com.google.gson.JsonObject
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.Item
import net.minecraft.item.tooltip.TooltipType
import java.nio.file.Files
import java.nio.file.Path

/**
 * 在容器界面中悬停指定槽位，手动渲染 tooltip 到帧缓冲，然后截图。
 *
 * 1.21.4 与 1.21.1 同代：使用传统的 DrawContext 即时渲染，不需要 Mixin
 * （1.21.11 依赖的 GuiRenderer 类在 1.21.5+ 才引入，1.21.4 不存在）。
 *
 * 流程：
 * 1. hoverSlot 移动光标 + 获取 tooltip 数据
 * 2. RuntimeTickScheduler.schedule(1) 延迟 1 tick
 * 3. 手动创建 DrawContext → screen.render() 重绘屏幕 → drawTooltip() 叠加 tooltip → draw() → 截图
 *
 * Action ID: "screenshot_tooltip"
 */
class ScreenshotTooltipAction : ActionExecutor {

    override fun execute(params: JsonObject): ActionResult =
        ActionResult.fail("screenshot_tooltip requires commandId")

    override fun execute(params: JsonObject, commandId: String): ActionResult {
        val windowId = params.requireInt("windowId")
        val slotIndex = params.requireInt("slot")
        val testId = params.get("testId")?.asString ?: "default"
        val prefix = params.get("prefix")?.asString ?: "tooltip"

        val client = MinecraftClient.getInstance()
        val screen = client.currentScreen as? HandledScreen<*>
            ?: return ActionResult.fail("No container screen open")

        // 1. hover 移动光标 + 获取 tooltip 数据
        val hoverState = try {
            ContainerTooltipHelper.hoverSlot(windowId, slotIndex)
        } catch (e: Exception) {
            return ActionResult.fail(e.message ?: "Failed to hover slot")
        }

        if (!hoverState.visible || hoverState.snapshot == null) {
            return ActionResult.fail("Slot $slotIndex is empty, no tooltip to render")
        }

        // 2. 获取 tooltip 文本行
        val handler = screen.screenHandler
        val slot = handler.slots[slotIndex]
        val player = client.player ?: return ActionResult.fail("Player not available")
        val tooltipLines = client.world?.let { world ->
            slot.stack.getTooltip(Item.TooltipContext.create(world), player, TooltipType.BASIC)
        } ?: Screen.getTooltipFromItem(client, slot.stack)

        val mouseX = hoverState.mouseX.toInt()
        val mouseY = hoverState.mouseY.toInt()

        // 3. 延迟 1 tick，手动渲染 + 截图
        RuntimeTickScheduler.schedule(1) {
            try {
                val currentScreen = client.currentScreen as? HandledScreen<*>
                if (currentScreen == null) {
                    RuntimeResponseSender.sendResponse(commandId, "failure", "Screen closed before screenshot")
                    return@schedule
                }

                // 手动创建 DrawContext（1.21.4 构造函数为 public，两参）
                val drawContext = DrawContext(client, client.bufferBuilders.entityVertexConsumers)

                // 重绘屏幕（partialTick 用 0f，因为在 tick 开始时执行）
                currentScreen.render(drawContext, mouseX, mouseY, 0f)

                // 叠加 tooltip
                drawContext.drawTooltip(client.textRenderer, tooltipLines, mouseX, mouseY)

                // flush 渲染命令
                drawContext.draw()

                // 截图
                captureScreenshot(commandId, testId, prefix, hoverState)
            } catch (e: Exception) {
                RuntimeResponseSender.sendResponse(commandId, "failure", "Render failed: ${e.message}")
            }
        }

        return ActionResult.async()
    }

    private fun captureScreenshot(
        commandId: String,
        testId: String,
        prefix: String,
        hoverState: ContainerTooltipHelper.TooltipState
    ) {
        val playerName = RuntimeScreenshotBridge.currentPlayerName() ?: "unknown"
        val baseDirectory = RuntimeScreenshotBridge.gameDirectory() ?: run {
            RuntimeResponseSender.sendResponse(commandId, "failure", "Game directory not available")
            return
        }
        val screenshotConfig = RuntimeBlackBoxConfig.current.screenshot
        val directory = baseDirectory
            .resolve(screenshotConfig.rootDirectory)
            .resolve(sanitize(playerName))
            .resolve(sanitize(testId))

        val index = nextIndex(directory)
        val indexStr = index.toString().padStart(3, '0')
        val fileName = "${indexStr}_${sanitize(prefix)}"

        RuntimeScreenshotBridge.captureAsync(directory, fileName) { result ->
            result.fold(
                onSuccess = { screenshot ->
                    val data = hoverState.toQueryJson().apply {
                        val relativePath = baseDirectory.relativize(screenshot.filePath)
                        addProperty("filePath", relativePath.toString().replace('\\', '/'))
                        addProperty("width", screenshot.width)
                        addProperty("height", screenshot.height)
                        addProperty("fileSize", screenshot.fileSize)
                        addProperty("index", index)
                    }
                    RuntimeResponseSender.sendResponse(commandId, "success", "Tooltip screenshot saved: $fileName.png", data)
                },
                onFailure = { error ->
                    RuntimeResponseSender.sendResponse(commandId, "failure", "Screenshot failed: ${error.message}")
                }
            )
        }
    }

    private fun nextIndex(directory: Path): Int {
        if (!Files.exists(directory)) return 1
        val files = directory.toFile().listFiles() ?: return 1
        return (files.mapNotNull { INDEX_PATTERN.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0) + 1
    }

    private fun sanitize(name: String): String = name.replace(SANITIZE_PATTERN, "_")

    companion object {
        private val INDEX_PATTERN = Regex("^(\\d+).*\\.png$")
        private val SANITIZE_PATTERN = Regex("[^a-zA-Z0-9_\\-.]")
    }
}
