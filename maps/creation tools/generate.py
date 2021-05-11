import pprint
import random


def generate(s=12, c=11, chosen='C01'):
    board = ['..........XX.....X..X..',
             '.XX..X.XX....X.X..X...X',
             '...X..XXX.X.X.X.XXX..X.',
             '.X.X.XXX.X..X......X...',
             '..X..X...X...XXX.X...XX',
             '...X..X.XXXX.....X.X...',
             'XX...X...X...XXXXXX..X.',
             '.X.X...X....XX......X..',
             '.XXX.XXX.XXX...XX.XXXX.',
             '...X....X...XXX.XX...X.',
             'XX.X.XXX..X.XX...X.X..X',
             '.........X.....X....X..']
    Sset = []
    Cset = []
    filled_board = []
    for i, row in enumerate(board):
        filled_board.append([])
        for j, item in enumerate(row):
            filled_board[i].append(0)
            if item == '.':
                Cset.append((i, j))
            else:
                Sset.append((i, j))

    random.shuffle(Sset)
    random.shuffle(Cset)

    si = 1
    for x, y in Sset:
        name = 'S'
        if si < 10:
            name += '0'
        name += str(si)
        filled_board[x][y] = name
        si += 1
        if si > s:
            si = 1

    ci = 1
    for x, y in Cset:
        name = 'C'
        if ci < 10:
            name += '0'
        name += str(ci)
        filled_board[x][y] = name
        ci += 1
        if ci > c:
            ci = 1

    #for i in range(len(filled_board)):
    #    for j in range(len(filled_board[i])):
    #        if filled_board[i][j] != chosen:
    #            filled_board[i][j] = '...'

    #for row in filled_board:
    #    print(row)

    return filled_board


generate()

