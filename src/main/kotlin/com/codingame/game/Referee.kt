package com.codingame.game

import com.codingame.gameengine.core.AbstractPlayer.TimeoutException
import com.codingame.gameengine.core.AbstractReferee
import com.codingame.gameengine.core.MultiplayerGameManager
import com.codingame.gameengine.module.entities.GraphicEntityModule
import com.google.inject.Inject

class Referee : AbstractReferee() {
    @Inject
    private lateinit var gameManager: MultiplayerGameManager<Player>
    @Inject
    private lateinit var graphicEntityModule: GraphicEntityModule

    override fun init() {
        // Initialize your game here.

    }

    override fun gameTurn(turn: Int) {
        for (player: Player in gameManager.activePlayers) {
            player.sendInputLine("input")
            player.execute()
        }

        for (player: Player in gameManager.activePlayers) {
            try {
                val outputs = player.outputs
                println(outputs)
            } catch (e: TimeoutException) {
                // kekere?
            }
        }
    }
}