import copy
import queue


class Bot(object):
    def __init__(self, x, y, sx, sy, v, limit):
        # init board
        self.width = x
        self.height = y
        self.board = []
        self.blocks = {-1: {}, 0: {}, 1: {}}
        for i in range(x):
            self.board.append([])
            for j in range(y):
                self.board[i].append(-1)
                self.blocks[-1][(x, y)] = True

        self.board[sx][sy] = 0
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

    def init_group(self, id):
        if id not in self.groups:
            self.groups[id] = {'interactive': -1, 'win': -1, 'blocks_vision': -1,
                               'unpassable': -1, 'kills': -1}

    def shift_template(self):
        return {"id": -2, "type": "unknown", "target_group": -2, "target_change": "", "stored_targets":[],
                "preshift": -2, "postshift" :-2, "stored_group": {}, "lever_position": (-1, -1)}

    def parse_vision(self, total, x, y, group, is_active, is_win):
        prev = self.board[x][y]
        self.board[x][y] = group
        if group != prev:
            self.blocks[prev].pop((x, y))
        if group not in self.blocks:
            self.blocks[group] = {}
        self.blocks[group][(x, y)] = True
        self.init_group(group)

        # check for 'interactive'
        self.groups[group]['interactive'] = is_active
        if (x, y) not in self.known_levers:
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
                self.board[gx][gy] = group
                self.blocks[group][(gx, gy)] = True
            self.blocks[prev] = {}
            self.groups[prev] = shift["stored_group"]

        # check for 'blocks_vision'
        if self.groups[group]['blocks_vision'] == -1:
            discerned = False
            decision = True
            if abs(self.cx - x) < self.vision and self.cy == y:
                if (x-1, y) in total and (x+1, y) in total:
                    discerned = True
                    decision = False
                else:
                    discerned = True
                    decision = True
            elif abs(self.cy - y) < self.vision and self.cx == x:
                if (x, y-1) in total and (x, y+1) in total:
                    discerned = True
                    decision = False
                else:
                    discerned = True
                    decision = True
            elif abs(self.cx - x) + abs(self.cy - y) < self.vision:
                all_clear = True
                for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                    tx = x + dx
                    ty = y + dy
                    if 0 <= tx < self.width and 0 <= ty < self.height:
                        if (tx, ty) not in total:
                            all_clear = False
                if all_clear:
                    discerned = True
                    decision = False

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
        if self.prev_action == "USE" and (self.cx, self.cy) in self.known_levers and changed_object is not None:
            id = self.known_levers[(self.cx, self.cy)]
            self.shifts[id]["target_group"] = changed_object
            self.apply_shift(self.shifts[id])

        elif self.prev_action not in ["USE", "PASS", "RESET"]:
            changes = []
            group = self.board[self.intent[0]][self.intent[1]]

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

            if len(self.shift_history) > 0 and self.shift_history[-1]["type"] == "unknown" and self.shift_history[-1]["target_group"] == group:
                for (prop, status) in changes:
                    if status != self.shift_history[-1]["stored_group"][prop] and self.shift_history[-1]["stored_group"][prop] != -1:
                        shift = self.shift_history[-1]
                        self.shifts[shift["id"]]["type"] = "change"
                        self.shift_history[-1]["type"] = "change"
                        self.shifts[shift["id"]]["target_group"] = group
                        self.shift_history[-1]["target_group"] = group
                        self.shifts[shift["id"]]["target_change"] = prop
                        self.shift_history[-1]["target_change"] = prop
                        self.groups[group] = shift["stored_group"]
                    self.groups[group][prop] = status

    def wide_search(self, sx, sy, target=-2, undiscovered_search=False):
        exploration_queue = queue.PriorityQueue()
        exploration_queue.put((0, ((sx, sy), 0, 'PASS', 0)))
        visited = {(sx, sy): True}

        while not exploration_queue.empty():
            point = exploration_queue.get()
            cx = point[0][0]
            cy = point[0][0]
            visited[(cx, cy)] = True

            if self.board[cx][cy] == target:
                return point[1], point[2], point[3]
            if undiscovered_search:
                for prop in self.groups[self.board[cx][cy]]:
                    if self.groups[self.board[cx][cy]][prop] == -1:
                        return point[1], point[2], point[3]

            for (dx, dy) in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                tx = cx + dx
                ty = cy + dy
                visited[(tx, ty)] = True
                if 0 <= tx < self.width and 0 <= ty < self.height and (tx, ty) not in visited:
                    proceed = False
                    if undiscovered_search:
                        for prop in self.groups[self.board[tx][ty]]:
                            if self.groups[self.board[tx][ty]][prop] == -1:
                                proceed = True
                    if self.groups[self.board[tx][ty]]['unpassable'] == False and \
                       self.groups[self.board[tx][ty]]['kills'] == False:
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
                        if point[3] == 0 and self.board[tx][ty] > 0:
                            obstacle = self.board[tx][ty]
                        exploration_queue.put((heuristic_length, ((tx, ty), heuristic_length, action, obstacle)))

        return -1, 'PASS', 0

    def find_path(self, sx, sy, gx, gy, undiscovered_traversal=False, fog_traversal=False, exploration_protocol=False):
        heuristic_length = abs(gx - sx) + abs(gy - sy)
        if exploration_protocol:
            heuristic_length = 0
        exploration_queue = queue.PriorityQueue()
        exploration_queue.put((heuristic_length, ((sx, sy), heuristic_length, 0, 'PASS', 0)))
        visited = {(sx, sy): True}

        while not exploration_queue.empty():
            point = exploration_queue.get()
            cx = point[0][0]
            cy = point[0][0]
            visited[(cx, cy)] = True

            if (exploration_protocol and self.board[cx][cy] == -1) or (cx == gx and cy == gy):
                return point[2], point[3], point[4]

            for (dx, dy) in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                tx = cx + dx
                ty = cy + dy
                visited[(tx, ty)] = True
                if 0 <= tx < self.width and 0 <= ty < self.height and \
                   (tx, ty) not in visited and \
                   ((undiscovered_traversal and self.groups[self.board[tx][ty]]['unpassable'] == -1) or \
                   (self.groups[self.board[tx][ty]]['unpassable'] == False)) and \
                   ((undiscovered_traversal and self.groups[self.board[tx][ty]]['kills'] == -1) or \
                   (self.groups[self.board[tx][ty]]['kills'] == False)) and \
                   ((fog_traversal and self.board[tx][ty] == -1) or self.board[tx][ty] >= 0):
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
                    if point[4] == 0 and self.board[tx][ty] > 0:
                        obstacle = self.board[tx][ty]
                    exploration_queue.put((heuristic_length, ((tx, ty), heuristic_length, point[2]+1, action, obstacle)))

        return -1, 'PASS', 0

    def return_choice(self, dir):
        self.prev_action = dir
        if dir == "UP":
            self.intent = (self.cx, self.cy+1)
        elif dir == "DOWN":
            self.intent = (self.cx, self.cy-1)
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
            # try straight path
            for (gx, gy) in self.flags:
                ln, dir, obstacle = self.find_path(self.cx, self.cy, gx, gy)
                if 0 <= ln < mx:
                    mx = ln
                    used_dir = dir
                    used_obstacle = obstacle
            if mx < 99999:
                return mx, used_dir, used_obstacle

            # try risky path
            for (gx, gy) in self.flags:
                ln, dir, obstacle = self.find_path(self.cx, self.cy, gx, gy, undiscovered_traversal=True)
                if 0 <= ln < mx:
                    mx = ln
                    used_dir = dir
                    used_obstacle = obstacle
            if mx < 99999:
                # find the closest known member of the group to spare yourself the pain
                for (bx, by) in self.blocks[used_obstacle]:
                    ln, dir, obstacle = self.find_path(self.cx, self.cy, bx, by, undiscovered_traversal=True)
                    if 0 <= ln < mx and obstacle == used_obstacle:
                        mx = ln
                        used_dir = dir
                return mx, used_dir, used_obstacle

            # try unknown path
            for (gx, gy) in self.flags:
                ln, dir, obstacle = self.find_path(self.cx, self.cy, gx, gy, undiscovered_traversal=True, fog_traversal=True)
                if 0 <= ln < mx:
                    mx = ln
                    used_dir = dir
                    used_obstacle = obstacle
            if mx < 99999:
                return mx, used_dir, used_obstacle

        return -1, 'PASS', 0

    def attempt_explore(self):
        if len(self.blocks[-1]) > 0:
            gx, gy = self.blocks[-1][0]
            ln, dir, obstacle = self.find_path(self.cx, self.cy, gx, gy, fog_traversal=True, exploration_protocol=True)
            if 0 <= ln < 99999:
                return ln, dir, obstacle

            ln, dir, obstacle = self.find_path(self.cx, self.cy, gx, gy, fog_traversal=True, undiscovered_traversal=True, exploration_protocol=True)
            if 0 <= ln < 99999:
                return ln, dir, obstacle

        return -1, 'PASS', 0

    def apply_shift(self, shift):
        self.shift_history.append(copy.deepcopy(shift))
        self.shift_history[-1]["stored_group"] = self.groups[shift["target_group"]]
        if shift["type"] == "change":
            if self.groups[shift["target_group"]]["target_change"]:
                self.groups[shift["target_group"]]["target_change"] = False
            else:
                self.groups[shift["target_group"]]["target_change"] = True
        elif shift["type"] == "unknown":
            self.init_group(shift["target_group"])
        elif shift["type"] == "transform":
            for (gx, gy) in self.blocks[shift["preshift"]]:
                self.shift_history[-1]["stored_targets"].append((gx, gy))
                self.board[gx][gy] = shift["postshift"]
                self.blocks[shift["postshift"]][(gx, gy)] = True
            self.blocks[shift["preshift"]] = {}

    def reverse_shift(self):
        if len(self.shift_history) > 0:
            shift = self.shift_history[-1]
            if shift["type"] == "transform":
                for (gx, gy) in shift["stored_targets"]:
                    self.board[gx][gy] = shift["preshift"]
                    self.blocks[shift["postshift"]].pop((gx, gy))
                    self.blocks[shift["preshift"]][(gx, gy)] = True
            elif shift["type"] == "change" or shift["type"] == "unknown":
                self.groups[shift["target_group"]] = shift["stored_group"]

    def reset_level(self):
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
                self.reverse_shift()

        if mn >= 9999999:
            return -1, 'PASS', False
        return mn, used_dir, found_flag

    def make_move(self):
        mx = 9999999
        # 1. if you found the flag, try to reach it
        ln, dir, obstacle = self.attempt_flag()
        if 0 <= ln < mx:
            return self.return_choice(dir)

        # 2. if you cant reach the flag, explore the level
        ln, dir, obstacle = self.attempt_explore()
        if 0 <= ln < mx:
            return self.return_choice(dir)

        # 3. if you pulled an unknown lever, try to find out what it did
        if len(self.shift_history) > 0:
            shift = self.shift_history[-1]
            if shift["type"] == "unknown":
                ln, dir, obstacle = self.wide_search(self.cx, self.cy, target=shift["target_group"])
                if 0 <= ln < 99999:
                    return self.return_choice(dir)

        # 4. try to use the levers you know to move onward
        ln, dir = self.shift_search(self.cx, self.cy, fst=True)
        if 0 <= ln < mx:
            return self.return_choice(dir)

        # 5. try to find out the properties of all known groups
        ln, dir, obstacle = self.wide_search(self.cx, self.cy, undiscovered_search=True)
        if 0 <= ln < mx:
            return self.return_choice(dir)

        # 6. experiment with new levers
        mn = mx
        used_dir = "PASS"
        for key in self.known_levers:
            shift = self.shifts[self.known_levers[key]]
            if shift["type"] == "unknown":
                ln, dir, obstacle = self.find_path(self.cx, self.cy, shift["lever_position"][0], shift["lever_position"][1])
            if 0 <= ln < mn:
                mn = ln
                used_dir = dir
        if mn < mx:
            if mn == 0:
                used_dir = "USE"
            return self.return_choice(used_dir)

        # 7. fuckin kys
        return self.return_choice("RESET")