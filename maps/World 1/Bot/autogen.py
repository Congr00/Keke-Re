import sys
import math

# Auto-generated code below aims at helping you parse
# the standard input according to the problem statement.

# w: width of the board
# h: height of the board
# sx: starting position on the board on x axis
# sy: starting position on the board on y axis
# v: radius of player's vision (default 9)
# limit: the maximum amount of turns until the player automatically loses
w, h, sx, sy, v, limit = [int(i) for i in input().split()]

# game loop
while True:
    # turn: current turn of the game
    # cx: current position on the board on x axis
    # cy: current position on the board on y axis
    turn, cx, cy = [int(i) for i in input().split()]

    # blocks_count: amount of blocks that the player can currently see
    blocks_count = int(input())
    for i in range(blocks_count):
        # bx: position of the block on x axis
        # by: position of the block on y axis
        # bgroup: the object group of the block
        # is_interactive: can the object be used? (0/1)
        # is_win: does the object have the 'win condition' property? (0/1)
        bx, by, bgroup, is_interactive, is_win = [int(i) for i in input().split()]

    # Write an action using print
    # To debug: print("Debug messages...", file=sys.stderr, flush=True)

    # action: LEFT, RIGHT, UP, DOWN, PASS, USE, RESET
    print("RIGHT")
