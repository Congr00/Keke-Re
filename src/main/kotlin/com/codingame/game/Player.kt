package com.codingame.game

import com.codingame.gameengine.core.AbstractMultiplayerPlayer

class Player: AbstractMultiplayerPlayer() {
    override fun getExpectedOutputLines(): Int = 1
}