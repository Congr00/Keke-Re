package com.codingame.game.engine

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import com.codingame.gameengine.module.entities.*
import java.nio.file.NoSuchFileException
import kotlin.math.ceil

class InfoDisplay(
    private var graphicEntityModule: GraphicEntityModule,
    private var stride: Int,
    private var scale: Double
) {
    enum class DisplayText(val varName: String) {
        SCORE("Score: %d"),
        DEATHS("Deaths: %d"),
        INTERACT_COUNT("Actions: %d"),
        RESETS("Resets: %d"),
        STEPS_COUNT("Steps: %d")
    }

    private var textSprites: HashMap<String, BitmapText> = HashMap()

    init {
        val freeSpace = ((1920 - (32 * scale * stride)))
        val menuItems = DisplayText.values().size
        val textPadding = 60
        val fSize = 22
        val yPadding = ((1080 - menuItems * fSize + menuItems * textPadding) / 3.2).toInt()
        DisplayText.values().forEachIndexed { i, el ->
            textSprites[el.varName] = graphicEntityModule.createBitmapText().apply {
                text = el.varName.format(0)
                font = "Joystix"
                fontSize = fSize
                x = (stride * 32 * scale + (freeSpace - 15 * (fSize + 2)) / 2).toInt()
                y = yPadding + i * fontSize + i * textPadding
                zIndex = 5
                tint = 0xffffff
                this.blendMode = BlendableEntity.BlendMode.SCREEN
            }
        }
    }

    fun updateValue(type: DisplayText, value: Int) {
        val currentValue = textSprites[type.varName]?.text?.split(":")?.get(1)?.strip()?.toInt()
        textSprites[type.varName]?.text = type.varName.format(value + currentValue!!)
    }
}


class SpriteManager(
    private var graphicEntityModule: GraphicEntityModule,
    private val entities: Array<java.util.ArrayList<AnyGameEntity>>,
    private var stride: Int,
    private var scale: Double
) {
    private var visionBlocks: Array<Sprite> = emptyArray()

    private class SpriteData(val sprite: Sprite, var id: Int, var text: Option<BitmapText> = None)

    private var spriteContainer: HashMap<String, ArrayList<SpriteData>> = HashMap()

    init {
        entities.forEach { el ->
            for (e in el) {
                if (e.hasTexture) {
                    val textureLoc = textureToLoc(e)
                    if (textureLoc !in spriteContainer) {
                        spriteContainer[textureLoc] = arrayListOf()
                    }
                    newSprite(textureLoc, e)
                }
            }


        }

        visionBlocks = Array(entities.size) { idx ->
            val x = idx % stride
            val y = idx / stride

            graphicEntityModule.createSprite().apply {
                image = "semi_transparent.png"
                setX((x * 32 * scale).toInt(), Curve.NONE)
                setY((y * 32 * scale).toInt(), Curve.NONE)
                setScale(scale)
                isVisible = true
                zIndex = 4
            }
        }

        createBackground()
    }

    fun getSpriteEntity(gameEntity: AnyGameEntity) : Option<Sprite> {
        if (gameEntity.hasTexture) {
            val textureLoc = textureToLoc(gameEntity)
            for (p in spriteContainer[textureLoc]!!) {
                if (p.id == gameEntity.spriteID && p.sprite.isVisible) {
                    return Some(p.sprite)
                }
            }
        }
        return None
    }

    private fun createBackground() {
        val backgroundScale = 2.5
        val availableWidth = ceil(((1920 - 32 * scale * stride)) / (32 * backgroundScale)).toInt()
        println(availableWidth)
        for (x in 0..(availableWidth + 1)) {
            for (y in 0..(1080 / (32 * backgroundScale) + 1).toInt()) {
                graphicEntityModule.createSprite().apply {
                    image = "cobble.png"
                    setX(ceil((stride * 32 * scale) - 10 + x * 32 * backgroundScale).toInt(), Curve.NONE)
                    setY(ceil(y * 32 * backgroundScale).toInt(), Curve.NONE)
                    setScale(backgroundScale)
                    isVisible = true
                    zIndex = -1
                }
            }
        }

    }

    private fun textureToLoc(e: AnyGameEntity): String {
        var textureLoc = e.texture.filepath
        val i = textureLoc.indexOf('*')
        if (i >= 0) {
            if (e.textureNum == -1) {
                throw NoSuchFileException("You need to specify texture number before loading it!")
            }
            textureLoc = textureLoc.replace("*", e.textureNum.toString())
        }
        return textureLoc
    }

    private fun newSpriteIndicatorText(entity: AnyGameEntity): Option<BitmapText> {
        if (entity.isInteractable) {
            return Some(graphicEntityModule.createBitmapText().apply {
                when (entity.interactionTarget) {
                    is ActionTarget.Group -> {
                        text = (entity.interactionTarget as ActionTarget.Group).gid.toString()
                    }
                    is ActionTarget.Template -> {
                        if ((entity.interactionTarget as ActionTarget.Template).tid < 2) {
                            return None
                        }
                        text = (entity.interactionTarget as ActionTarget.Template).tid.toString()
                    }
                }
                font = "Joystix"
                fontSize = (8 * scale / 2).toInt()
                x = if (text.length == 1) ((entity.position.x + 1) * scale * 32 - 19 * scale).toInt()
                    else ((entity.position.x + 1) * scale * 32 - 22 * scale).toInt()
                y = ((entity.position.y + 1) * scale * 32 - 10 * scale).toInt()
                zIndex = 2
                tint = 0xf93130
            })
        } else if (entity.hasTemplate) {
            if (entity.tid > 1) {
                return Some(graphicEntityModule.createBitmapText().apply {
                    text = entity.tid.toString()
                    font = "Joystix"
                    fontSize = (5 * scale / 1.5).toInt()
                    x = ((entity.position.x + 1) * scale * 32 - 7 * scale).toInt()
                    y = ((entity.position.y + 1) * scale * 32 - 8 * scale).toInt()
                    zIndex = 5
                    tint = 0xffffff
                })
            }
        }
        return None
    }

    private fun newSprite(hash: String, entity: AnyGameEntity) {
        spriteContainer[hash]?.add(SpriteData(graphicEntityModule.createSprite().apply {
            image = hash
            setX((entity.position.x * 32 * scale).toInt(), Curve.NONE)
            setY((entity.position.y * 32 * scale).toInt(), Curve.NONE)
            setScale(scale)
            zIndex = entity.texture.zIndex
        }, entity.spriteID, newSpriteIndicatorText(entity)))
    }

    fun swapButton(btn: AnyGameEntity) {
        if (btn.hasTexture) {
            when (btn.texture.filepath) {
                Textures.BUTTON_ON.filepath -> {
                    freeSprite(btn)
                    btn.texture = Textures.BUTTON_OFF
                    allocateSprite(btn, btn.position)
                }
                Textures.BUTTON_OFF.filepath -> {
                    freeSprite(btn)
                    btn.texture = Textures.BUTTON_ON
                    allocateSprite(btn, btn.position)
                }
            }
        }
    }

    fun setShadow(id: Int, value: Boolean) {
        visionBlocks[id].isVisible = value
    }

    fun moveSprite(entity: AnyGameEntity, newPosition: Position) {
        if (entity.hasTexture) {
            val textureLoc = textureToLoc(entity)
            for (p in spriteContainer[textureLoc]!!) {
                if (p.id == entity.spriteID && p.sprite.isVisible) {
                    p.sprite.x = (newPosition.x * 32 * scale).toInt()
                    p.sprite.y = (newPosition.y * 32 * scale).toInt()
                    if (p.text is Some) {
                        if (entity.isInteractable) {
                            (p.text as Some<BitmapText>).value.x =
                                ((entity.position.x + 1) * scale * 32 - 18 * scale).toInt()
                            (p.text as Some<BitmapText>).value.y =
                                ((entity.position.y + 1) * scale * 32 - 10 * scale).toInt()
                        } else {
                            (p.text as Some<BitmapText>).value.x =
                                ((entity.position.x + 1) * scale * 32 - 7 * scale).toInt()
                            (p.text as Some<BitmapText>).value.y =
                                ((entity.position.y + 1) * scale * 32 - 7 * scale).toInt()
                        }
                    }
                    break
                }
            }
        }
    }

    fun freeSprite(entity: AnyGameEntity) {
        if (entity.hasTexture) {
            val textureLoc = textureToLoc(entity)
            for (p in spriteContainer[textureLoc]!!) {
                if (p.id == entity.spriteID) {
                    p.sprite.isVisible = false
                    if (p.text is Some) {
                        (p.text as Some<BitmapText>).value.isVisible = false
                    }
                    break
                }
            }
        }
    }

    fun allocateSprite(entity: AnyGameEntity, position: Position) {
        if (entity.hasTexture) {
            val textureLoc = textureToLoc(entity)
            if (textureLoc !in spriteContainer) {
                spriteContainer[textureLoc] = arrayListOf()
                newSprite(textureLoc, entity)
            } else {
                var allocated = false
                for (p in spriteContainer[textureLoc]!!) {
                    if (!p.sprite.isVisible) {
                        p.sprite.x = (position.x * 32 * scale).toInt()
                        p.sprite.y = (position.y * 32 * scale).toInt()
                        p.id = entity.spriteID
                        p.sprite.isVisible = true
                        allocated = true
                        if (p.text is None) {
                            p.text = newSpriteIndicatorText(entity)
                        }
                        if (entity.isInteractable) {
                            val text = when (entity.interactionTarget) {
                                is ActionTarget.Group -> {
                                    (entity.interactionTarget as ActionTarget.Group).gid.toString()
                                }
                                is ActionTarget.Template -> {
                                    if ((entity.interactionTarget as ActionTarget.Template).tid < 2) {
                                        break
                                    } else {
                                        (entity.interactionTarget as ActionTarget.Template).tid.toString()
                                    }
                                }
                                else -> {
                                    break
                                }
                            }
                            (p.text as Some<BitmapText>).value.text = text
                            (p.text as Some<BitmapText>).value.x =
                                if (text.length == 1) ((entity.position.x + 1) * scale * 32 - 19 * scale).toInt()
                                else ((entity.position.x + 1) * scale * 32 - 22 * scale).toInt()
                            (p.text as Some<BitmapText>).value.y =
                                ((entity.position.y + 1) * scale * 32 - 10 * scale).toInt()
                            (p.text as Some<BitmapText>).value.isVisible = true
                            (p.text as Some<BitmapText>).value.tint = 0xf93130
                            (p.text as Some<BitmapText>).value.fontSize = (8 * scale / 2).toInt()
                            (p.text as Some<BitmapText>).value.zIndex = 2
                        } else if (entity.hasTemplate) {
                            if (entity.tid < 2) {
                                break
                            }
                            (p.text as Some<BitmapText>).value.text = entity.tid.toString()
                            (p.text as Some<BitmapText>).value.x =
                                ((entity.position.x + 1) * scale * 32 - 7 * scale).toInt()
                            (p.text as Some<BitmapText>).value.y =
                                ((entity.position.y + 1) * scale * 32 - 8 * scale).toInt()
                            (p.text as Some<BitmapText>).value.isVisible = true
                            (p.text as Some<BitmapText>).value.tint = 0xffffff
                            (p.text as Some<BitmapText>).value.fontSize = (5 * scale / 1.5).toInt()
                            (p.text as Some<BitmapText>).value.zIndex = 5
                        }
                        break
                    }
                }
                if (!allocated) {
                    newSprite(textureLoc, entity)
                }
            }
        }
    }

    fun reloadMap(entities: Array<java.util.ArrayList<AnyGameEntity>>) {
        for (block in visionBlocks) {
            block.isVisible = true
        }
        for (list in spriteContainer.values) {
            for (p in list) {
                p.sprite.isVisible = false
                if (p.text is Some) {
                    (p.text as Some<BitmapText>).value.isVisible = false
                }
            }
        }
        entities.forEach { el ->
            for (e in el) {
                allocateSprite(e, e.position)
            }
        }
    }
}