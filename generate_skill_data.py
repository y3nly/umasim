import sqlite3
import json
import os
import re
import urllib.request

# --- CONFIGURATION ---
BASE_JSON_URL = "https://raw.githubusercontent.com/mee1080/umasim/main/data/skill_data.txt"
DB_FILE = "master.mdb"
OUTPUT_FILE = "skill_data.txt"

NAME_CAT = 47
DESC_CAT = 48

COND_REGEX = re.compile(r'([a-zA-Z0-9_]+)(==|!=|>=|<=|>|<)([^&]+)')

EFFECT_NAMES = {
    "targetSpeed": "Target Speed",
    "acceleration": "Acceleration",
    "heal": "Heal",
    "currentSpeed": "Current Speed",
    "speedWithDecel": "Speed (Decel)",
    "laneChangeSpeed": "Lane Change Speed"
}


def dict_factory(cursor, row):
    d = {}
    for idx, col in enumerate(cursor.description):
        d[col[0]] = row[idx]
    return d


def parse_logic_string(logic_str):
    if not logic_str: return []
    or_blocks = []
    raw_blocks = str(logic_str).split('@')
    for block in raw_blocks:
        if not block.strip(): continue
        and_conditions = []
        parts = block.split('&')
        for part in parts:
            part = part.strip()
            if not part: continue
            match = COND_REGEX.match(part)
            if match:
                c_type, c_op, c_val = match.groups()
                try:
                    if '.' in c_val:
                        final_val = float(c_val)
                        if final_val.is_integer(): final_val = int(final_val)
                    else:
                        final_val = int(c_val)
                except ValueError:
                    final_val = c_val
                and_conditions.append({"type": c_type, "operator": c_op, "value": final_val})
        if and_conditions:
            or_blocks.append(and_conditions)
    return or_blocks


def generate_summary_string(logic_str, effects, duration):
    if logic_str:
        blocks = str(logic_str).split('@')
        formatted_blocks = [f"[{b.strip()}]" for b in blocks if b.strip()]
        cond_text = " OR ".join(formatted_blocks)
    else:
        cond_text = ""

    eff_strs = []
    for e in effects:
        raw_type = e.get('type', 'Unknown')
        eff_name = EFFECT_NAMES.get(raw_type, raw_type)
        eff_val = e.get('value', 0)
        eff_strs.append(f"{eff_name} {eff_val}")

    eff_text = ", ".join(eff_strs)
    dur_text = f"Duration {duration}" if duration > 0 else "Instant"

    parts = [p for p in [cond_text, eff_text, dur_text] if p]
    return ", ".join(parts)


def merge():
    print("Fetching base data from GitHub...")
    try:
        with urllib.request.urlopen(BASE_JSON_URL) as response:
            skill_list = json.loads(response.read().decode('utf-8'))
    except Exception as e:
        print(f"Download failed: {e}")
        return

    if not os.path.exists(DB_FILE):
        print(f"Missing {DB_FILE}")
        return

    print("Loading database...")
    try:
        conn = sqlite3.connect(f"file:{DB_FILE}?mode=ro", uri=True)
        conn.row_factory = dict_factory
        cursor = conn.cursor()

        # Load race mechanics
        cursor.execute("SELECT * FROM skill_data")
        db_skills = {str(row['id']): row for row in cursor.fetchall()}

        # Load text translations
        cursor.execute(f"SELECT * FROM text_data WHERE category IN ({NAME_CAT}, {DESC_CAT})")
        text_rows = cursor.fetchall()
        names_map = {str(r['index']): r['text'] for r in text_rows if r['category'] == NAME_CAT}
        desc_map = {str(r['index']): r['text'] for r in text_rows if r['category'] == DESC_CAT}

    except sqlite3.Error as e:
        print(f"DB Error: {e}")
        return
    finally:
        if 'conn' in locals(): conn.close()

    print("Updating skills...")
    patched_count = 0

    for skill in skill_list:
        skill_id = str(skill['id'])

        if skill_id not in db_skills:
            continue

        patched_count += 1
        db_row = db_skills[skill_id]

        # Update translated name
        if skill_id in names_map:
            skill['name'] = names_map[skill_id]

        # Process descriptions
        new_info = []
        if skill_id in desc_map:
            new_info.append(desc_map[skill_id])
        else:
            old_info = skill.get('info', [""])
            new_info.append(old_info[0] if old_info else "")

        # Process and format skill invokes
        if 'invokes' in skill:
            for invoke in skill['invokes']:
                idx = invoke.get('index')
                if not idx: continue

                cond_col = f"condition_{idx}"
                raw_logic = db_row.get(cond_col, "")

                if cond_col in db_row and raw_logic:
                    invoke['conditions'] = parse_logic_string(raw_logic)

                if 'duration' in db_row:
                    invoke['duration'] = float(db_row['duration'])

                if 'cd' in invoke and 'cooldown_time' in db_row:
                    invoke['cd'] = float(db_row['cooldown_time'])

                current_logic = raw_logic if raw_logic else ""
                existing_effects = invoke.get('effects', [])
                current_dur = invoke.get('duration', 0.0)

                if current_logic or existing_effects:
                    summary = generate_summary_string(current_logic, existing_effects, current_dur)
                    new_info.append(summary)

            if len(new_info) > 1:
                skill['info'] = new_info

    print(f"Saving to {OUTPUT_FILE}...")
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(skill_list, f, indent=None, separators=(',', ':'), ensure_ascii=False)

    print(f"Done! Updated {patched_count} skills.")


if __name__ == "__main__":
    merge()