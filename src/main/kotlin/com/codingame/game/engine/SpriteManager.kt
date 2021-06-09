package com.codingame.game.engine

import com.codingame.gameengine.module.entities.Curve
import com.codingame.gameengine.module.entities.GraphicEntityModule
import com.codingame.gameengine.module.entities.Sprite
import java.nio.file.NoSuchFileException


class SpriteManager(
    private var graphicEntityModule: GraphicEntityModule,
    private val entities: Array<java.util.ArrayList<AnyGameEntity>>,
    private var stride: Int,
    private var scale: Double
) {
    private var visionBlocks: Array<Sprite> = emptyArray()

    private class SpritePair(val sprite: Sprite, var id: Int)

    private var spriteContainer: HashMap<String, ArrayList<SpritePair>> = HashMap()

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

                if (e.isInteractable){
                    graphicEntityModule.createBitmapText().apply {
                        when (e.interactionTarget) {
                            is ActionTarget.Group -> {
                                text = (e.interactionTarget as ActionTarget.Group).gid.toString()
                            }
                            is ActionTarget.Template -> {
                                text = (e.interactionTarget as ActionTarget.Template).tid.toString()
                            }
                        }
                        font = "Pixeled"
                        fontSize = 8
                        x = ((e.position.x + 1) * scale * 32 - 18 * scale).toInt()
                        y = ((e.position.y + 1) * scale * 32 - 12 * scale).toInt()
                        zIndex = 2
                        tint = 0xf93130
                    }
                }
                else if (e.hasTemplate){
                    if (e.tid > 1) {
                        graphicEntityModule.createBitmapText().apply {
                            text = e.tid.toString()
                            font = "Pixeled"
                            fontSize = 5
                            x = ((e.position.x + 1) * scale * 32 - 7 * scale).toInt()
                            y = ((e.position.y + 1) * scale * 32 - 7 * scale).toInt()
                            zIndex = 2
                        }
                    }
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

    private fun newSprite(hash: String, entity: AnyGameEntity) {
        spriteContainer[hash]?.add(SpritePair(graphicEntityModule.createSprite().apply {
            image = hash
            setX((entity.position.x * 32 * scale).toInt(), Curve.NONE)
            setY((entity.position.y * 32 * scale).toInt(), Curve.NONE)
            setScale(scale)
            zIndex = entity.texture.zIndex
        }, entity.spriteID))
    }

    fun swapButton(btn: AnyGameEntity){
        if (btn.hasTexture){
            when (btn.texture.filepath){
                Textures.BUTTON_ON.filepath -> {
                    freeSprite(btn)
                    btn.texture = Textures.BUTTON_OFF
                    allocateSprite(btn, btn.position)
                }
                Textures.BUTTON_OFF.filepath ->{
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
            }
        }
        entities.forEach { el ->
            for (e in el) {
                allocateSprite(e, e.position)
            }
        }
    }
}