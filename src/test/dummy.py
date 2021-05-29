import sys


w, h = [int(i) for i in input().split()]
#actions = ["RIGHT", "RIGHT", "RIGHT", "RIGHT", "RIGHT", "USE", "LEFT", "UP", "UP", "UP", "UP", "UP"]
actions = """
UP
UP
RIGHT
RIGHT
USE
RIGHT
RIGHT
USE
UP
UP
UP
UP
LEFT
LEFT
LEFT
LEFT
UP
UP
UP
UP
UP
UP
UP
UP
UP
UP
UP
""".strip().split("\n")
ci = 0
while True:
    cells = int(input())
    for i in range(cells):
        cx, cy, n = [int(i) for i in input().split()]
        for j in range(n):
            rs = str(input())
            print(f"{cx}, {cy}, {rs}", file=sys.stderr)

    print(actions[ci])
    ci += 1
    if ci >= len(actions):
        ci = 0