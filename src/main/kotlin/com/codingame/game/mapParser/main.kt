package com.codingame.game.mapParser

import arrow.core.None
import com.codingame.game.engine.*
import com.codingame.gameengine.runner.SoloGameRunner
import org.w3c.dom.Document
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.round

fun readMap(fileName: String): Triple < Array<ArrayList<EntityBuilder>>, Int, MutableMap<Int, EntityBuilder> > {
    val xlmFile: File = File(fileName)
    val xmlDoc: Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xlmFile)
    val mapHeight: Int = xmlDoc.getElementsByTagName("map").item(0).attributes.getNamedItem("height").nodeValue.toInt()
    val mapWidth: Int = xmlDoc.getElementsByTagName("map").item(0).attributes.getNamedItem("width").nodeValue.toInt()
    val tileDensity: Int =
        xmlDoc.getElementsByTagName("map").item(0).attributes.getNamedItem("tilewidth").nodeValue.toInt()
    //System.err.println(mapHeight)
    //System.err.println(mapWidth)
    //System.err.println(tileDensity)

    val floor = EntityBuilder{
        newGameEntityOfType(Terrain) {
            attributes(
                Template(0),
                EntityTexture(texture = Textures.FLOOR, textureNum = 0),
                EntityPosition()
            )
        }
    }

    val mapIndex: (Int, Int) -> Int = { x, y -> y * mapWidth + x }
    val map = Array(mapWidth * mapHeight) { arrayListOf(floor) }

    val wall = EntityBuilder{
        newGameEntityOfType(Terrain) {
            attributes(
                Template(1),
                Immovable(),
                VisionBlocker(),
                EntityTexture(texture = Textures.WALLS, textureNum = 0),
                EntityPosition()
            )
        }
    }

    val tiles: String = xmlDoc.getElementsByTagName("data").item(0).textContent
    //System.err.println(tiles)
    val tilesList: List<String> = tiles.split(",")
    var y: Int = -1
    var x: Int = -1
    var width: Int? = null

    for (tile in tilesList) {
        var usedTile = tile.removeSuffix('\n'.toString())
        if (!tile[0].isDigit()) {
            if (width == null && x > 0) {
                width = x
            }

            y += 1
            x = 0
            usedTile = tile.removePrefix('\n'.toString())
        }
        if (usedTile.toInt() == 11) {
            //System.err.println("$x $y wall")
            map[mapIndex(x, y)].add(wall) // wall
        }
        x += 1
    }

    val objectList: NodeList = xmlDoc.getElementsByTagName("object")
    val templateList: MutableMap<Int, EntityBuilder> = mutableMapOf()
    templateList[0] = floor
    templateList[1] = wall

    for (i in 0 until objectList.length) {
        val type: String = objectList.item(i).attributes.getNamedItem("type").nodeValue
        var x: Int = objectList.item(i).attributes.getNamedItem("x").nodeValue.toInt()
        var y: Int = objectList.item(i).attributes.getNamedItem("y").nodeValue.toInt()
        x = round(x.toDouble() / tileDensity).toInt()
        y = round(y.toDouble() / tileDensity).toInt() - 1
        if (type == "Start") {
            // x and y are the position of the initial spawn
            val player = EntityBuilder {
                newGameEntityOfType(Player) {
                    attributes(
                        EntityTexture(texture = Textures.KEKE),
                        EntityPosition(),
                        Immovable()
                        )
                    facets(Movable(), Killable(spawnPoint = None)) // Set to None for RESET to work
                    behaviors(InputReceiver)
                }}
            map[mapIndex(x, y)].add(player)
        } else if (type == "Static") {
            val properties: NodeList = objectList.item(i).childNodes.item(1).childNodes
            val group: Int = properties.item(1).attributes.getNamedItem("value").nodeValue.toInt()
            val objectProperties: List<String> = properties.item(3).attributes.getNamedItem("value").nodeValue
                .removeSuffix('\n'.toString()).split(", ").filter{it.isNotEmpty()}

            //System.err.println(group)
            //System.err.println(objectProperties)

            if (templateList.contains(group)) {
                map[mapIndex(x, y)].add(templateList[group]!!)
                continue
            }

            val builder = EntityBuilder {
                newGameEntityOfType(Terrain) {
                    attributes(
                        EntityPosition(),
                        Template(group)
                    )
                    facets(Transmutable())
                }
            }
            for (property in objectProperties) {
                when (property) {
                    "win" -> {
                        builder.modify(EntityBuilder.AddAttribute { WinPoint() })
                    }
                    "blocks vision" -> {
                        builder.modify(EntityBuilder.AddAttribute { VisionBlocker() })
                    }
                    "unpassable" -> {
                        builder.modify(EntityBuilder.AddAttribute { Immovable() })
                    }
                    "kills" -> {
                        builder.modify(EntityBuilder.AddFacet {
                            Steppable(
                                stepAction = ActionType.GameMessage { context, entity -> Kill(context, entity) },
                                stepActionTarget = ActionTarget.Self
                            )
                        })
                    }
                    else -> throw Exception("Property $property is not implemented")
                }
            }

            val item = builder.build()
            if (item.isWinPoint) {
                builder.modify(EntityBuilder.AddAttribute { EntityTexture(texture = Textures.FINISH) })
            } else if (item.isImmovable && item.blocksVision) {
                builder.modify(EntityBuilder.AddAttribute { EntityTexture(texture = Textures.WALLS, textureNum = 6) })
            } else if (item.isImmovable && !item.blocksVision) {
                builder.modify(EntityBuilder.AddAttribute { EntityTexture(texture = Textures.WINDOW) })
            } else if (!item.isImmovable && item.blocksVision) {
                builder.modify(EntityBuilder.AddAttribute { EntityTexture(texture = Textures.CURTAIN) })
            } else if (item.isSteppable) {
                builder.modify(EntityBuilder.AddAttribute { EntityTexture(texture = Textures.SPIKE) })
            } else {
                builder.modify(EntityBuilder.AddAttribute { EntityTexture(texture = Textures.LAVA, textureNum = 0) })
            }

            map[mapIndex(x, y)].add(builder)
            templateList[group] = builder
        } else if (type == "Interactive") {
            val properties: NodeList = objectList.item(i).childNodes.item(1).childNodes
            val group: Int = properties.item(1).attributes.getNamedItem("value").nodeValue.toInt()
            val target: Int = properties.item(3).attributes.getNamedItem("value").nodeValue.toInt()
            if (properties.item(5).attributes.getNamedItem("name").nodeValue == "Transform onto") {
                val transmute: Int = properties.item(5).attributes.getNamedItem("value").nodeValue.toInt()
                // make transmute button
                val button = EntityBuilder {
                    newGameEntityOfType(Terrain) {
                        attributes(
                            EntityPosition(),
                            EntityTexture(texture = Textures.BUTTON_ON),
                        )
                        facets(
                            Interactable(
                                interaction = ActionType.GameMessage { ctx, source ->
                                    Transmute(
                                        ctx,
                                        source,
                                        transmute
                                    )
                                },
                                interactionTarget = ActionTarget.Template(target)
                            )
                        )
                    }}
                map[mapIndex(x, y)].add(button)
            } else if (properties.item(5).attributes.getNamedItem("name").nodeValue == "toggle property") {
                val change: String = properties.item(5).attributes.getNamedItem("value").nodeValue
                // make change button
                val interaction = when(change) {
                    "win" -> {
                        EntityBuilder.ToggleAttribute(WinPoint::class) { WinPoint() }
                    }
                    "blocks vision" -> {
                        EntityBuilder.ToggleAttribute(VisionBlocker::class) { VisionBlocker() }
                    }
                    "unpassable" -> {
                        EntityBuilder.ToggleAttribute(Immovable::class) { Immovable() }
                    }
                    "kills" -> {
                        EntityBuilder.ToggleFacet(Steppable::class) { Steppable(
                            stepAction = ActionType.GameMessage { context, entity -> Kill(context, entity) },
                            stepActionTarget = ActionTarget.Self
                        )}
                    }
                    else -> throw Exception("Interaction $change is not implemented")
                }
                val button = EntityBuilder {
                    newGameEntityOfType(Terrain) {
                        attributes(
                            EntityPosition(),
                            EntityTexture(texture = Textures.BUTTON_ON),
                        )
                        facets(
                            Interactable(
                                interaction = ActionType.Modify(
                                    interaction
                                ),
                                interactionTarget = ActionTarget.Template(target)
                            )
                        )
                    }
                }

                map[mapIndex(x, y)].add(button)
            }
        }

    }

    return Triple(map, width!!, templateList)
}

fun main(args: Array<String>) {
    readMap("G:\\Alterpath\\Keke-Re\\maps\\World 1\\map1.tmx")
    val gameRunner = SoloGameRunner()
    // gameRunner.setAgent(Agent::class.java)
    gameRunner.setAgent("/bin/bash /home/marwit/agent.sh")
    gameRunner.setTestCaseInput("0\n0\n0\n0\n0")
    gameRunner.start()
}