import copy
import queue
import sys
from collections import deque

class Bot(object):
    def __init__(self, x, y, sx, sy, v, limit):
        # init board
        self.width = x
        self.height = y
        self.board = []
        self.blocks = {-1: {}, 0: {}, 1: {}}
        for i in range(y):
            self.board.append([])
            for j in range(x):
                self.board[i].append(-1)
                self.blocks[-1][(j, i)] = True

        self.board[sy][sx] = 0
        self.spawn = (sx, sy)
        self.cx = sx
        self.cy = sy
        self.vision = v
        self.turn_limit = limit
        self.turn = 0
        self.groups = {}
        self.shifts = []
        self.known_levers = {}
        self.flags = {}
        self.shift_history = []
        self.intent = (-1, -1)
        self.prev_pos = (self.cx, self.cy)
        self.prev_action = "PASS"

        # init unknown
        self.init_group(-1)

        # init floor
        self.init_group(0)
        self.groups[0]['interactive'] = False
        self.groups[0]['win'] = False
        self.groups[0]['blocks_vision'] = False
        self.groups[0]['unpassable'] = False
        self.groups[0]['kills'] = False

        # init wall
        self.init_group(1)
        self.groups[1]['interactive'] = False
        self.groups[1]['win'] = False
        self.groups[1]['blocks_vision'] = True
        self.groups[1]['unpassable'] = True
        self.groups[1]['kills'] = False

    def init_group(self, id, reset=False):
        if id not in self.groups or reset:
            self.groups[id] = {'interactive': -1, 'win': -1, 'blocks_vision': -1,
                               'unpassable': -1, 'kills': -1}

    def shift_template(self):
        return {"id": -2, "type": "unknown", "target_group": -2, "target_change": "", "stored_targets":[],
                "preshift": -2, "postshift" :-2, "stored_group": {}, "lever_position": (-1, -1)}

    def change_position(self, cx, cy):
        self.cx = cx
        self.cy = cy

    def parse_vision(self, total, x, y, group, is_active, is_win):
        prev = self.board[y][x]
        self.board[y][x] = group
        if group != prev:
            self.blocks[prev].pop((x, y))
        if group not in self.blocks:
            self.blocks[group] = {}
        self.blocks[group][(x, y)] = True
        self.init_group(group)
        if is_active:
            is_active = True

        # check for 'interactive'
        self.groups[group]['interactive'] = is_active
        if is_active and (x, y) not in self.known_levers:
            self.known_levers[(x, y)] = len(self.shifts)
            shift = copy.deepcopy(self.shift_template())
            shift["id"] = len(self.shifts)
            shift["lever_position"] = (x, y)
            self.shifts.append(shift)

        # check for 'win'
        if len(self.shift_history) > 0 and self.shift_history[-1]["type"] == "unknown" and self.shift_history[-1]["target_group"] == group and \
                is_win != self.shift_history[-1]["stored_group"]["win"] and self.shift_history[-1]["stored_group"]["win"] != -1:
            shift = self.shift_history[-1]
            self.shifts[shift["id"]]["type"] = "change"
            self.shift_history[-1]["type"] = "change"
            self.shifts[shift["id"]]["target_group"] = group
            self.shift_history[-1]["target_group"] = group
            self.shifts[shift["id"]]["target_change"] = "win"
            self.shift_history[-1]["target_change"] = "win"
            self.groups[group] = shift["stored_group"]

        self.groups[group]['win'] = is_win
        if is_win:
            self.flags[(x, y)] = True
        if not is_win and (x, y) in self.flags:
            self.flags.pop((x, y))

        # check for transformation
        if len(self.shift_history) > 0 and self.shift_history[-1]["type"] == "unknown" and self.shift_history[-1]["target_group"] == prev and \
                prev >= 0 and prev != group:
            shift = self.shift_history[-1]
            self.shifts[shift["id"]]["type"] = "transform"
            self.shift_history[-1]["type"] = "transform"
            self.shifts[shift["id"]]["preshift"] = prev
            self.shift_history[-1]["preshift"] = prev
            self.shifts[shift["id"]]["postshift"] = group
            self.shift_history[-1]["postshift"] = group
            self.shift_history[-1]["stored_targets"].append((x, y))
            for (gx, gy) in self.blocks[prev]:
                self.shift_history[-1]["stored_targets"].append((gx, gy))
                self.board[gy][gx] = group
                self.blocks[group][(gx, gy)] = True
            self.blocks[prev] = {}
            self.groups[prev] = shift["stored_group"]

        # check for 'blocks_vision'
        if self.groups[group]['blocks_vision'] == -1:
            discerned = False
            decision = True
            if 0 <= x-1 and x+1 < self.width and abs(self.cx - x) < self.vision and self.cy == y:
                if (x-1, y) in total and (x+1, y) in total:
                    discerned = True
                    decision = False
                else:
                    discerned = True
                    decision = True
            elif 0 <= y-1 and y+1 < self.height and abs(self.cy - y) < self.vision and self.cx == x:
                if (x, y-1) in total and (x, y+1) in total:
                    discerned = True
                    decision = False
                else:
                    discerned = True
                    decision = True
            elif 0 < abs(self.cx - x) + abs(self.cy - y) < self.vision-1 and abs(self.cx - x) == abs(self.cy - y):
                if x > self.cx:
                    tx = 1
                else:
                    tx = -1
                if y > self.cy:
                    ty = 1
                else:
                    ty = -1
                if 0 <= x+tx < self.width and 0 <= y+ty < self.height:
                    if (x+tx, y+ty) in total and (x-tx, y-ty) in total:
                        discerned = True
                        decision = False
                    else:
                        discerned = True
                        decision = True

            if discerned:
                if len(self.shift_history) > 0 and self.shift_history[-1]["type"] == "unknown" and self.shift_history[-1]["target_group"] == group and \
                        decision != self.shift_history[-1]["stored_group"]["blocks_vision"] and self.shift_history[-1]["stored_group"]["blocks_vision"] != -1:
                    shift = self.shift_history[-1]
                    self.shifts[shift["id"]]["type"] = "change"
                    self.shift_history[-1]["type"] = "change"
                    self.shifts[shift["id"]]["target_group"] = group
                    self.shift_history[-1]["target_group"] = group
                    self.shifts[shift["id"]]["target_change"] = "blocks_vision"
                    self.shift_history[-1]["target_change"] = "blocks_vision"
                    self.groups[group] = shift["stored_group"]

                self.groups[group]['blocks_vision'] = decision

    def parse_intent(self, died=False, changed_object=None):
        print(f"intent: {died}, {changed_object}", file=sys.stderr)
        if self.prev_action == "USE" and (self.cx, self.cy) in self.known_levers:
            id = self.known_levers[(self.cx, self.cy)]
            if changed_object is not None and changed_object is not False and changed_object is not True:
                self.shifts[id]["target_group"] = changed_object
            self.apply_shift(self.shifts[id])

        elif self.prev_action == "RESET":
            self.reset_level()

        elif self.prev_action not in ["USE", "PASS", "RESET"]:
            changes = []
            group = self.board[self.intent[1]][self.intent[0]]

            if self.intent[0] == self.cx and self.intent[1] == self.cy:
                self.groups[group]["unpassable"] = False
                self.groups[group]["kills"] = False
                changes.append(("unpassable", False))
                changes.append(("kills", False))

            if self.prev_pos[0] == self.cx and self.prev_pos[1] == self.cy:
                self.groups[group]["unpassable"] = True
                changes.append(("unpassable", True))

            if ((self.prev_pos[0] != self.cx or self.prev_pos[1] != self.cy) and (self.intent[0] != self.cx or self.intent[1] != self.cy) and \
                self.cx == self.spawn[0] and self.cy == self.spawn[1]) or died:
                self.groups[group]["kills"] = True
                self.groups[group]["unpassable"] = False
                changes.append(("unpassable", False))
                changes.append(("kills", True))
                died = True

            if len(self.shift_history) > 0 and self.shift_history[-1]["type"] == "unknown" and self.shift_history[-1]["target_group"] == group:
                shift = self.shift_history[-1]
                for (prop, status) in changes:
                    if status != shift["stored_group"][prop] and shift["stored_group"][prop] != -1:
                        self.shifts[shift["id"]]["type"] = "change"
                        self.shift_history[-1]["type"] = "change"
                        #self.shifts[shift["id"]]["target_group"] = group
                        #self.shift_history[-1]["target_group"] = group
                        self.shifts[shift["id"]]["target_change"] = prop
                        self.shift_history[-1]["target_change"] = prop
                        self.groups[group] = copy.deepcopy(shift["stored_group"])
                    self.groups[group][prop] = status

                # check if last shift is unknown, check if there is only one remaining possibility
                if shift["type"] == "unknown":
                    calc_poss = 0
                    chosen = None
                    for prop in self.groups[group]:
                        if self.groups[group][prop] == -1:
                            calc_poss += 1
                            chosen = prop
                    if calc_poss == 1:
                        self.shifts[shift["id"]]["type"] = "change"
                        self.shift_history[-1]["type"] = "change"
                        self.shifts[shift["id"]]["target_change"] = chosen
                        self.shift_history[-1]["target_change"] = chosen

            if died:
                self.reset_level()

    def wide_search(self, sx, sy, target=-2, undiscovered_search=False, undiscovered_traversal=False, exploration_protocol=False):
        exploration_queue = deque()
        exploration_queue.append(((sx, sy), 0, 'PASS', 0))
        visited = {(sx, sy): True}
        #print(f"wide_search: {sx}, {sy}, target: {target}", file=sys.stderr)
        while exploration_queue:
            point = exploration_queue.popleft()
            #print(f"{point}", file=sys.stderr)
            cx = point[0][0]
            cy = point[0][1]
            visited[(cx, cy)] = True

            if self.board[cy][cx] == target:
                return point[1], point[2], point[3]
            if undiscovered_search:
                for prop in self.groups[self.board[cy][cx]]:
                    if self.groups[self.board[cy][cx]][prop] == -1:
                        return point[1], point[2], point[3]

            for (dx, dy) in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                tx = cx + dx
                ty = cy + dy
                if 0 <= tx < self.width and 0 <= ty < self.height and (tx, ty) not in visited:
                    visited[(tx, ty)] = True
                    proceed = False
                    if exploration_protocol and self.board[ty][tx] == -1:
                        proceed = True
                    if undiscovered_search:
                        for prop in self.groups[self.board[ty][tx]]:
                            if self.groups[self.board[ty][tx]][prop] == -1 and \
                                    not(prop == 'kills' and self.groups[self.board[ty][tx]]['unpassable'] == True):
                                proceed = True
                    if ((undiscovered_traversal and self.groups[self.board[ty][tx]]['unpassable'] == -1) or
                         (self.groups[self.board[ty][tx]]['unpassable'] == False)) and \
                        ((undiscovered_traversal and self.groups[self.board[ty][tx]]['kills'] == -1) or
                         (self.groups[self.board[ty][tx]]['kills'] == False)):
                        proceed = True
                    if self.groups[self.board[ty][tx]]['unpassable'] == False and \
                            self.groups[self.board[ty][tx]]['kills'] == False:
                        proceed = True
                    if proceed:
                        heuristic_length = point[1]+1
                        action = point[2]
                        if point[1] == 0:
                            action = 'PASS'
                            if dx == -1:
                                action = 'LEFT'
                            if dx == 1:
                                action = 'RIGHT'
                            if dy == -1:
                                action = 'UP'
                            if dy == 1:
                                action = 'DOWN'
                        obstacle = point[3]
                        if point[3] == 0 and self.board[ty][tx] > 0:
                            obstacle = self.board[ty][tx]
                        exploration_queue.append(((tx, ty), heuristic_length, action, obstacle))

        return -1, 'PASS', 0

    def find_path(self, sx, sy, gx, gy, undiscovered_traversal=False, fog_traversal=False, exploration_protocol=False):
        heuristic_length = abs(gx - sx) + abs(gy - sy)
        if exploration_protocol:
            heuristic_length = 0
        exploration_queue = queue.PriorityQueue()
        exploration_queue.put((heuristic_length, ((sx, sy), heuristic_length, 0, 'PASS', 0)))
        visited = {(sx, sy): True}
        #print(f"find_path: {sx}, {sy}, {gx}, {gy}", file=sys.stderr)

        while not exploration_queue.empty():
            point = exploration_queue.get()[1]
            #print(point, file=sys.stderr)
            cx = point[0][0]
            cy = point[0][1]
            visited[(cx, cy)] = True

            if (exploration_protocol and self.board[cy][cx] == -1) or (cx == gx and cy == gy):
                return point[2], point[3], point[4]

            for (dx, dy) in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                tx = cx + dx
                ty = cy + dy
                if 0 <= tx < self.width and 0 <= ty < self.height and \
                        (tx, ty) not in visited and \
                        ((undiscovered_traversal and self.groups[self.board[ty][tx]]['unpassable'] == -1) or \
                         (self.groups[self.board[ty][tx]]['unpassable'] == False)) and \
                        ((undiscovered_traversal and self.groups[self.board[ty][tx]]['kills'] == -1) or \
                         (self.groups[self.board[ty][tx]]['kills'] == False)) and \
                        ((fog_traversal and self.board[ty][tx] == -1) or self.board[ty][tx] >= 0):
                    visited[(tx, ty)] = True
                    heuristic_length = abs(gx - tx) + abs(gy - ty) + point[2]
                    if exploration_protocol:
                        heuristic_length = point[2]+1
                    action = point[3]
                    if point[2] == 0:
                        action = 'PASS'
                        if dx == -1:
                            action = 'LEFT'
                        if dx == 1:
                            action = 'RIGHT'
                        if dy == -1:
                            action = 'UP'
                        if dy == 1:
                            action = 'DOWN'
                    obstacle = point[4]
                    if point[4] == 0 and self.board[ty][tx] > 0:
                        gid = self.board[ty][tx]
                        if self.groups[gid]['unpassable'] or self.groups[gid]['kills']:
                            obstacle = self.board[ty][tx]
                    exploration_queue.put((heuristic_length, ((tx, ty), heuristic_length, point[2]+1, action, obstacle)))

        return -1, 'PASS', 0

    def return_choice(self, dir):
        self.prev_action = dir
        if dir == "UP":
            self.intent = (self.cx, self.cy-1)
        elif dir == "DOWN":
            self.intent = (self.cx, self.cy+1)
        elif dir == "LEFT":
            self.intent = (self.cx-1, self.cy)
        elif dir == "RIGHT":
            self.intent = (self.cx+1, self.cy)
        else:
            self.intent = (self.cx, self.cy)
        self.prev_pos = (self.cx, self.cy)
        return dir

    def attempt_flag(self):
        used_dir = 'PASS'
        used_obstacle = 0
        mx = 9999999
        if len(self.flags) > 0:
            # try unknown path
            for (gx, gy) in self.flags:
                ln, dir, obstacle = self.find_path(self.cx, self.cy, gx, gy, undiscovered_traversal=True, fog_traversal=True)
                if 0 <= ln < mx:
                    mx = ln
                    used_dir = dir
                    used_obstacle = obstacle
            if mx >= 99999:
                return -1, 'PASS', 0

            # try risky path
            mx2 = 9999999
            for (gx, gy) in self.flags:
                ln, dir, obstacle = self.find_path(self.cx, self.cy, gx, gy, undiscovered_traversal=True)
                if 0 <= ln < mx2:
                    mx2 = ln
                    used_dir = dir
                    used_obstacle = obstacle
            if mx2 >= 99999:
                return mx, used_dir, used_obstacle

            # try straight path
            mx3 = 9999999
            for (gx, gy) in self.flags:
                ln, dir, obstacle = self.find_path(self.cx, self.cy, gx, gy)
                if 0 <= ln < mx3:
                    mx3 = ln
                    used_dir = dir
                    used_obstacle = obstacle
            if mx3 >= 99999:
                # find the closest known member of the group to spare yourself the pain
                if used_obstacle >= 0:
                    mx4 = 999999
                    for (bx, by) in self.blocks[used_obstacle]:
                        ln, dir, obstacle = self.find_path(self.cx, self.cy, bx, by, undiscovered_traversal=True)
                        if 0 <= ln < mx4 and obstacle == used_obstacle:
                            mx4 = ln
                            used_dir = dir
                    return mx4, used_dir, used_obstacle
                return mx2, used_dir, used_obstacle

            return mx3, used_dir, used_obstacle

        return -1, 'PASS', 0

    def attempt_explore(self):
        ln, dir, obstacle = self.wide_search(self.cx, self.cy, target=-1, exploration_protocol=True)
        if 0 <= ln < 99999:
            return ln, dir, obstacle

        ln, dir, obstacle = self.wide_search(self.cx, self.cy, target=-1, exploration_protocol=True, undiscovered_traversal=True)
        if 0 <= ln < 99999:
            return ln, dir, obstacle
        """
        for (gx, gy) in self.blocks[-1]:
            ln, dir, obstacle = self.find_path(self.cx, self.cy, gx, gy, fog_traversal=True, exploration_protocol=True)
            if 0 <= ln < 99999:
                return ln, dir, obstacle

            ln, dir, obstacle = self.find_path(self.cx, self.cy, gx, gy, fog_traversal=True, undiscovered_traversal=True, exploration_protocol=True)
            if 0 <= ln < 99999:
                return ln, dir, obstacle

            return -1, 'PASS', 0
        """
        return -1, 'PASS', 0

    def apply_shift(self, shift):
        self.shift_history.append(copy.deepcopy(shift))
        if shift["target_group"] not in self.groups:
            self.init_group(shift["target_group"])
        self.shift_history[-1]["stored_group"] = copy.deepcopy(self.groups[shift["target_group"]])
        if shift["type"] == "change":
            #print(self.shift_history[-1]["stored_group"], file=sys.stderr)
            if self.groups[shift["target_group"]][shift["target_change"]]:
                self.groups[shift["target_group"]][shift["target_change"]] = False
            else:
                self.groups[shift["target_group"]][shift["target_change"]] = True
            #print(self.shift_history[-1]["stored_group"], file=sys.stderr)
        elif shift["type"] == "unknown":
            self.init_group(shift["target_group"], reset=True)
        elif shift["type"] == "transform":
            for (gx, gy) in self.blocks[shift["preshift"]]:
                self.shift_history[-1]["stored_targets"].append((gx, gy))
                self.board[gy][gx] = shift["postshift"]
                self.blocks[shift["postshift"]][(gx, gy)] = True
            self.blocks[shift["preshift"]] = {}

    def reverse_shift(self):
        if len(self.shift_history) > 0:
            shift = self.shift_history.pop()
            #print(f"{shift['preshift']}, {shift['postshift']}", file=sys.stderr)
            if shift["type"] == "transform":
                for (gx, gy) in shift["stored_targets"]:
                    self.board[gy][gx] = shift["preshift"]
                    self.blocks[shift["postshift"]].pop((gx, gy))
                    self.blocks[shift["preshift"]][(gx, gy)] = True
            elif shift["type"] == "change" or shift["type"] == "unknown":
                print(shift["stored_group"], file=sys.stderr)
                if shift["type"] == "change":
                    group = self.groups[shift["target_group"]]
                    for prop in group:
                        if shift["stored_group"][prop] == -1 and group[prop] != -1:
                            shift["stored_group"][prop] = group[prop]
                            if shift["target_change"] == prop:
                                if shift["stored_group"][prop]:
                                    shift["stored_group"][prop] = False
                                else:
                                    shift["stored_group"][prop] = True
                self.groups[shift["target_group"]] = shift["stored_group"]


    def reset_level(self):
        print(f"reset_level: {len(self.shift_history)}", file=sys.stderr)
        while len(self.shift_history) > 0:
            self.reverse_shift()

    def shift_search(self, cx, cy, fst=False):
        if not fst:
            ln, dir, obstacle = self.attempt_flag()
            if ln >= 0:
                return ln, dir, True
            ln, dir, obstacle = self.attempt_explore()
            if ln >= 0:
                return ln, dir, False

        mn = 9999999
        used_dir = 'PASS'
        found_flag = False
        for shift in self.shifts:
            proceed = False
            if shift["type"] == "transform":
                if len(self.blocks[shift["preshift"]]) > 0:
                    proceed = True

            elif shift["type"] == "change":
                if (shift["target_change"] in ["kills", "unpassable"] and self.groups[shift["target_group"]][shift["target_change"]] == True) or \
                        (shift["target_change"] in ["win"] and self.groups[shift["target_group"]][shift["target_change"]] == False):
                    proceed = True

            if proceed:
                print(f"applying: {shift}", file=sys.stderr)
                self.apply_shift(shift)
                ans, _, flag = self.shift_search(shift["lever_position"][0], shift["lever_position"][1])
                if 0 <= ans:
                    dist, dir, _ = self.find_path(cx, cy, shift["lever_position"][0], shift["lever_position"][1])
                    if 0 <= dist:
                        if (flag and not found_flag) or \
                                (((not flag and not found_flag) or (flag and found_flag)) and ans + dist < mn):
                            mn = ans + dist
                            used_dir = dir
                            if dist == 0:
                                used_dir = "USE"
                print(f"errasing: {shift}", file=sys.stderr)
                self.reverse_shift()

        if mn >= 9999999:
            return -1, 'PASS', False
        return mn, used_dir, found_flag

    def make_move(self):
        mx = 9999999
        # 1. if you found the flag, try to reach it
        ln, dir, obstacle = self.attempt_flag()
        if 0 <= ln < mx:
            print("get flag", file=sys.stderr)
            return self.return_choice(dir)

        # 2. if you cant reach the flag, explore the level
        ln, dir, obstacle = self.attempt_explore()
        if 0 <= ln < mx:
            print("explore level", file=sys.stderr)
            return self.return_choice(dir)

        # 3. if you pulled an unknown lever, try to find out what it did
        if len(self.shift_history) > 0:
            shift = self.shift_history[-1]
            if shift["type"] == "unknown":
                ln, dir, obstacle = self.wide_search(self.cx, self.cy, target=shift["target_group"])
                if 0 <= ln < 99999:
                    print("find lever change", file=sys.stderr)
                    return self.return_choice(dir)

        # 4. try to use the levers you know to move onward
        ln, dir, _ = self.shift_search(self.cx, self.cy, fst=True)
        if 0 <= ln < mx:
            print("shift world", file=sys.stderr)
            return self.return_choice(dir)

        # 5. try to find out the properties of all known groups
        ln, dir, obstacle = self.wide_search(self.cx, self.cy, undiscovered_search=True)
        if 0 <= ln < mx:
            print("explore groups", file=sys.stderr)
            return self.return_choice(dir)

        # 6. experiment with new levers
        mn = mx
        used_dir = "PASS"
        for key in self.known_levers:
            shift = self.shifts[self.known_levers[key]]
            if shift["type"] == "unknown" and shift["target_group"] == -2:
                ln, dir, obstacle = self.find_path(self.cx, self.cy, shift["lever_position"][0], shift["lever_position"][1])
            if 0 <= ln < mn:
                mn = ln
                used_dir = dir
        if mn < mx:
            if mn == 0:
                used_dir = "USE"
            print("explore levers", file=sys.stderr)
            return self.return_choice(used_dir)

        # 7. fuckin kys
        print("kill me", file=sys.stderr)
        return self.return_choice("RESET")


w, h, limit = [int(i) for i in input().split()]
init = False

while True:
    cells, sx, sy = [int(i) for i in input().split()]
    if not init:
        bot = Bot(w, h, sx, sy, 4, limit)
        init = True
    bot.change_position(sx, sy)

    used_cells = {}
    total_cells = []
    changed_object = False
    died = False
    for i in range(cells):
        cx, cy, n = [int(i) for i in input().split()]
        #print(f"{i}: {n}", file=sys.stderr)
        chosen_group = -2
        chosen_win = False
        chosen_interactive = False
        for j in range(n):
            props = str(input())
            group = -2
            win = False
            interactive = False
            for prop in props.split(","):
                if prop[0] == "O":
                    group = int(prop[12:])
                if prop[0] == "W":
                    win = True
                if prop[0] == "I":
                    if prop[9] == "?":
                        interactive = True
                    else:
                        interactive = int(prop[9:])
            if interactive or group > chosen_group:
                if interactive:
                    chosen_group = 9999
                else:
                    chosen_group = group
                chosen_win = win
                chosen_interactive = interactive

        total_cells.append([cx, cy, chosen_group, chosen_interactive, chosen_win])
        #print(f"{cx}, {cy}, {chosen_group}, {chosen_interactive}, {chosen_win}", file=sys.stderr)
        used_cells[(cx, cy)] = True
        if (cx, cy) in bot.known_levers and cx == sx and cy == sy and chosen_interactive and chosen_interactive is not True:
            id = bot.known_levers[(bot.cx, bot.cy)]
            if bot.shifts[id]["target_group"] == -2:
                changed_object = chosen_interactive


    bot.parse_intent(died, changed_object)
    for cell in total_cells:
        bot.parse_vision(used_cells, cell[0], cell[1], cell[2], cell[3], cell[4])

    for key in bot.groups:
        print(f"{key}: {bot.groups[key]}", file=sys.stderr)
    #for i in range(len(bot.shifts)):
    #    print(f"{i}: {bot.shifts[i]}", file=sys.stderr)
    #for i in range(len(bot.shift_history)):
    #    print(f"{i}: {bot.shift_history[i]}", file=sys.stderr)
    #for i in range(bot.height):
    #    print(bot.board[i], file=sys.stderr)
    print(bot.make_move())
