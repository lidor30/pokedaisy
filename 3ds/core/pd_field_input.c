#include "pd_binput.h"
#include <string.h>
#include "polished_action_data.h"
enum field_phase {
  OPEN_START, PICK_START, WAIT_BAG, PICK_ITEM, ITEM_MENU, USE_ITEM, HEAL_EFFECT,
  FLY_PARTY, FLY_SUBMENU, FLY_MAP,
};

enum { START_PARTY = 1, START_PACK = 2 };
enum { USE_BICYCLE = 1, USE_REPEL, USE_POTION };

static void advance(struct pd_field_input *a, enum field_phase phase) {
  if (a->phase != (unsigned)phase)
    a->phaseFrame = a->frames;
  a->phase = phase;
}

/* Observation values are generated from app data and pinned official symbols. */
static bool read8(pd_read_fn r, void *c, uint32_t a, uint8_t *v) {
  return r(c, a, v, 1);
}
static bool field(pd_read_fn r, void *c) {
  uint8_t state, script, map, battle;
  return read8(r, c, PD_PC_STATE, &state) && read8(r, c, PD_PC_SCRIPT, &script) &&
         read8(r, c, PD_PC_MAP, &map) && read8(r, c, PD_PC_BATTLE, &battle) &&
         !(state & 0x40) && script == 0 && map == 2 && battle == 0;
}
const char *pd_field_input_reason(const char *sha, pd_read_fn r, void *c) {
  if (!sha || strcmp(sha, PD_PC_ACTION_SHA)) return "Not supported in this version";
  uint8_t menu, running, events, queue;
  if (!read8(r, c, PD_PC_IN_MENU, &menu) || !read8(r, c, PD_PC_SCRIPT_RUNNING, &running) ||
      !read8(r, c, PD_PC_MAP_EVENTS, &events) || !read8(r, c, PD_PC_QUEUE, &queue))
    return "Cannot read game state";
  return !menu && !running && !events && !queue && field(r, c) ? NULL : "Return to the overworld first";
}

/* tmhm2.asm uses DoMenuJoypadLoop rather than the scrolling-menu header.
 * Require its complete menu geometry, flags and visible waiting cursor. */
static bool tm_pocket_waiting(pd_read_fn r,void *c) {
  uint8_t pocket,phase,inMenu,geometry[8],cursor[2],tile;
  if(!read8(r,c,PD_PC_POCKET,&pocket) || pocket!=3 ||
     !read8(r,c,PD_PC_PACK_STATE,&phase) || phase!=2 ||
     !read8(r,c,PD_PC_IN_MENU,&inMenu) || inMenu!=1 ||
     !r(c,PD_PC_MENU2D,geometry,sizeof geometry) ||
     !r(c,PD_PC_CURSOR,cursor,sizeof cursor))return false;
  if(geometry[0]!=2 || geometry[1]!=7 || geometry[2]<1 || geometry[2]>5 ||
     geometry[3]!=1 || geometry[4]!=0x0c || geometry[5]!=0 ||
     geometry[6]!=0x20 || geometry[7]!=0xfb || cursor[0]<1 ||
     cursor[0]>geometry[2] || cursor[1]!=1)return false;
  unsigned row=2+2*(cursor[0]-1);
  return read8(r,c,PD_PC_TILEMAP+row*20+7,&tile) && tile==PD_PC_CURSOR_TILE;
}
bool pd_field_input_begin(struct pd_field_input *a, const char *sha,
                         pd_read_fn r, void *c) {
  if (a->result == PD_FIELD_RUNNING)
    return false;
  *a = (struct pd_field_input){.result = PD_FIELD_REJECTED, .target = START_PACK};
  if (pd_field_input_reason(sha, r, c))
    return false;
  a->result = PD_FIELD_RUNNING;
  return true;
}
int pd_field_input_flyer(const char *sha,pd_read_fn r,void *c) {
  uint8_t count,mon[PD_PC_PARTY_SIZE];
  if(!sha || strcmp(sha,PD_PC_ACTION_SHA) || !read8(r,c,PD_PC_PARTY_COUNT,&count) || count>6)return -2;
  for(unsigned i=0;i<count;i++) {
    if(!r(c,PD_PC_PARTY_MON+i*PD_PC_PARTY_SIZE,mon,sizeof mon))return -2;
    uint8_t form=mon[PD_PC_PARTY_EGG_OFFSET];
    if((!mon[0] && !(form&0x20)) || (form&PD_PC_PARTY_EGG_MASK))continue;
    for(unsigned m=0;m<4;m++)if(mon[PD_PC_PARTY_MOVES_OFFSET+m]==PD_PC_FLY_MOVE)return (int)i;
  }
  return -1;
}
const char *pd_field_input_fly_reason(const char *sha,pd_read_fn r,void *c) {
  const char *why = pd_field_input_reason(sha, r, c);
  if (why) return why;
  uint8_t badges;
  if(!read8(r,c,PD_PC_BADGES,&badges))return "Cannot read badges";
  if(!(badges&0x20))return "Storm Badge required";
  int flyer=pd_field_input_flyer(sha,r,c);
  return flyer==-2 ? "Cannot read party" : flyer<0 ? "No one in your party knows Fly" : NULL;
}
bool pd_field_input_begin_fly(struct pd_field_input *a,const char *sha,const uint8_t *rom,
                             size_t size,pd_read_fn r,void *c) {
  if(!pd_field_input_begin(a,sha,r,c))return false;
  a->target=START_PARTY;a->result=PD_FIELD_REJECTED;
  if(pd_field_input_fly_reason(sha,r,c) || !rom || PD_PC_PARTY_SIZE>sizeof a->partyRecord)return false;
  /* The ROM's MonMenuOptions maps moves to menu IDs. Don't maintain a second
   * MONMENUITEM_FLY constant/table: read the actual version-matched table. */
  size_t at=(PD_PC_MON_OPTIONS>>16)*0x4000u+(PD_PC_MON_OPTIONS&0x3fffu);
  unsigned found=0;bool terminated=false;
  for(unsigned i=0;i<32;i++,at+=3) {
    if(at>=size)return false;
    if(rom[at]==255){terminated=true;break;}
    if(size-at<3)return false;
    if(rom[at]==0 && rom[at+2]==PD_PC_FLY_MOVE){found++;a->flyMenu=rom[at+1];}
  }
  if(!terminated || found!=1 || !a->flyMenu || a->flyMenu>=64)return false;
  int slot=pd_field_input_flyer(sha,r,c);if(slot<0)return false;
  a->slot=(unsigned)slot;
  if(!r(c,PD_PC_PARTY_MON+a->slot*PD_PC_PARTY_SIZE,a->partyRecord,PD_PC_PARTY_SIZE))return false;
  a->result=PD_FIELD_RUNNING;return true;
}
static bool menu_cursor(pd_read_fn r, void *c, int top, int left, unsigned rows, unsigned *row) {
  uint8_t m[7], cur[2], tile;
  if (!r(c, PD_PC_MENU2D, m, sizeof m) || !r(c, PD_PC_CURSOR, cur, sizeof cur) ||
      (top >= 0 && m[0] != top) || (left >= 0 && m[1] != left) || (rows && m[2] != rows) ||
      m[3] != 1 || m[6] != 0x20 || !cur[0] || cur[0] > m[2] || cur[1] != 1)
    return false;
  const unsigned y = m[0] + 2 * (cur[0] - 1);
  if (y >= 18 || m[1] >= 20 || !read8(r, c, PD_PC_TILEMAP + y * 20 + m[1], &tile) || tile != PD_PC_CURSOR_TILE)
    return false;
  *row = cur[0];
  return true;
}

static unsigned pick_row(struct pd_field_input *a, unsigned row, unsigned want, enum field_phase next) {
  if (++a->stable < 4)
    return 0;
  a->stable = 0;
  if (row != want)
    return row < want ? PD_KEY_DOWN : PD_KEY_UP;
  advance(a, next);
  return PD_KEY_A;
}

/* TownMapBubble (pokegear.asm) draws these corners and the Fly arrows into wTilemap.
 * A refused Fly can also leave the move submenu, so wait for the actual map. */
static bool fly_map_ready(pd_read_fn r, void *c) {
  uint8_t top[18], middle, bottom[18], menu;
  return read8(r, c, PD_PC_IN_MENU, &menu) && menu == 1 &&
         r(c, PD_PC_TILEMAP + 1, top, sizeof top) && top[0] == 0x40 && top[17] == 0x41 &&
         read8(r, c, PD_PC_TILEMAP + 38, &middle) && middle == 0x44 &&
         r(c, PD_PC_TILEMAP + 41, bottom, sizeof bottom) && bottom[0] == 0x42 && bottom[17] == 0x43;
}

static unsigned select_fly(struct pd_field_input *a, pd_read_fn r, void *c) {
  uint8_t count, mon[PD_PC_PARTY_SIZE], curParty, kind, items[9];
  unsigned row;
  if (!read8(r, c, PD_PC_PARTY_COUNT, &count) || a->slot >= count || count > 6 ||
      !r(c, PD_PC_PARTY_MON + a->slot * PD_PC_PARTY_SIZE, mon, sizeof mon) ||
      memcmp(mon, a->partyRecord, sizeof mon)) {
    a->result = PD_FIELD_REJECTED;
    return 0;
  }
  if (a->phase == FLY_MAP && fly_map_ready(r, c)) {
    if (++a->stable >= 4) a->result = PD_FIELD_DONE;
    return 0;
  }
  if (a->phase != FLY_PARTY &&
      read8(r, c, PD_PC_CUR_PARTY, &curParty) && curParty == a->slot &&
      read8(r, c, PD_PC_MON_COUNT, &kind) && kind > 0 && kind <= 8 &&
      r(c, PD_PC_MON_LIST, items, kind + 1) && items[kind] == 255 &&
      menu_cursor(r, c, 18 - 2 * kind, 7, kind, &row)) {
    uint64_t seen = 0;
    unsigned want = 0;
    for (unsigned i = 0; i < kind; i++) {
      if (!items[i] || items[i] >= 64 || (seen & (1ull << items[i]))) {
        a->result = PD_FIELD_REJECTED;
        return 0;
      }
      seen |= 1ull << items[i];
      if (items[i] == a->flyMenu) want = i + 1;
    }
    if (!want) {
      a->result = PD_FIELD_REJECTED;
      return 0;
    }
    return pick_row(a, row, want, FLY_MAP);
  }
  uint8_t action;
  if (a->phase != FLY_MAP && read8(r, c, PD_PC_PARTY_MENU, &action) && action == 0 &&
      menu_cursor(r, c, 1, 0, count + 1, &row))
    return pick_row(a, row, a->slot + 1, FLY_SUBMENU);
  a->stable = 0;
  return 0;
}
static bool item_stack(pd_read_fn r,void *c,unsigned p,unsigned slot,
                       unsigned item,unsigned qty) {
  unsigned char raw[152];unsigned max=pd_pc_item_pockets[p].max;
  if(max>75 || !r(c,pd_pc_item_pockets[p].addr,raw,2+2*max))return false;
  unsigned n=raw[0];
  return n<=max && raw[1+2*n]==255 && slot<n &&
         raw[1+2*slot]==item && raw[2+2*slot]==qty;
}
/* Key items are a zero-terminated list, not {id,quantity} pairs. Preserve
 * their game-owned ordering and reject duplicates / missing terminators. */
static bool key_slot(pd_read_fn r,void *c,unsigned item,unsigned *slot) {
  unsigned char keys[PD_PC_KEY_MAX+1];
  if(item<=0x200 || item>0x200+PD_PC_KEY_ID_MAX || !r(c,PD_PC_KEYS,keys,sizeof keys))return false;
  bool seen[256]={0},found=false;
  for(unsigned i=0;i<sizeof keys;i++) {
    if(!keys[i])return found;
    if(i==PD_PC_KEY_MAX || keys[i]>PD_PC_KEY_ID_MAX || seen[keys[i]])return false;
    seen[keys[i]]=true;
    if(keys[i]==item-0x200){*slot=i;found=true;}
  }
  return false;
}
static bool selected_stack(const struct pd_field_input *a,pd_read_fn r,void *c) {
  if(a->item>0x200) {
    unsigned slot=0;
    return key_slot(r,c,a->item,&slot) && slot==a->slot;
  }
  return item_stack(r,c,a->pocket,a->slot,a->item,a->quantity);
}
bool pd_field_input_begin_item(struct pd_field_input *a,const char *sha,unsigned item,
                              pd_read_fn r,void *c) {
  if(!pd_field_input_begin(a,sha,r,c))return false;
  a->result=PD_FIELD_REJECTED;
  if(item>0x200 && item<0x300) {
    if(!key_slot(r,c,item,&a->slot))return false;
    a->item=item;a->quantity=1;a->result=PD_FIELD_RUNNING;return true;
  }
  if(!item || item>=255)return false; /* TM namespace needs its own adapter. */
  unsigned matches=0;
  for(unsigned p=0;p<sizeof pd_pc_item_pockets/sizeof *pd_pc_item_pockets;p++) {
    unsigned char raw[152];unsigned max=pd_pc_item_pockets[p].max;
    if(max>75 || !r(c,pd_pc_item_pockets[p].addr,raw,2+2*max))return false;
    unsigned n=raw[0];if(n>max || raw[1+2*n]!=255)return false;
    for(unsigned i=0;i<n;i++)if(raw[1+2*i]==item) {
      matches++;a->pocket=p;a->slot=i;a->quantity=raw[2+2*i];
    }
  }
  if(matches!=1 || !a->quantity)return false;
  a->item=item;a->result=PD_FIELD_RUNNING;return true;
}
bool pd_field_input_begin_use(struct pd_field_input *a,const char *sha,unsigned item,
                            pd_read_fn r,void *c) {
  if(!pd_field_input_begin_item(a,sha,item,r,c))return false;
  a->result=PD_FIELD_REJECTED;
  uint8_t value;
  if(item==PD_PC_BICYCLE) {
    if(!read8(r,c,PD_PC_PLAYER_STATE,&value) || value>1)return false;
    a->useKind=USE_BICYCLE;a->beforeState=value;
  } else {
    bool repel=false;
    for(unsigned i=0;i<sizeof pd_pc_repels/sizeof *pd_pc_repels;i++)repel=repel || item==pd_pc_repels[i].id;
    if(!repel || !read8(r,c,PD_PC_REPEL_STEPS,&value) || value)return false;
    a->useKind=USE_REPEL;
  }
  a->result=PD_FIELD_RUNNING;return true;
}
bool pd_field_input_begin_heal(struct pd_field_input *a,const char *sha,unsigned item,unsigned slot,pd_read_fn r,void *c) {
  if(!pd_field_input_begin_item(a,sha,item,r,c))return false;
  a->result=PD_FIELD_REJECTED;uint8_t count;
  if(item!=PD_PC_POTION || !read8(r,c,PD_PC_PARTY_COUNT,&count) || count>6 || slot>=count ||
     !r(c,PD_PC_PARTY_MON+slot*PD_PC_PARTY_SIZE,a->partyRecord,PD_PC_PARTY_SIZE))return false;
  unsigned hp=(a->partyRecord[PD_PC_HP_OFFSET]<<8)|a->partyRecord[PD_PC_HP_OFFSET+1];
  unsigned max=(a->partyRecord[PD_PC_HP_OFFSET+2]<<8)|a->partyRecord[PD_PC_HP_OFFSET+3];
  if(!hp || hp>=max || (a->partyRecord[PD_PC_PARTY_EGG_OFFSET]&PD_PC_PARTY_EGG_MASK))return false;
  a->healSlot=slot;a->beforeHP=hp;a->useKind=USE_POTION;a->result=PD_FIELD_RUNNING;return true;
}
static bool remaining_quantity(const struct pd_field_input *a,pd_read_fn r,void *c,unsigned *qty) {
  uint8_t raw[152];unsigned max=pd_pc_item_pockets[a->pocket].max;
  if(max>75 || !r(c,pd_pc_item_pockets[a->pocket].addr,raw,2+2*max) || raw[0]>max || raw[1+2*raw[0]]!=255)return false;
  *qty=0;bool found=false;
  for(unsigned i=0;i<raw[0];i++)if(raw[1+2*i]==a->item) {
    if(found)return false;
    found=true;*qty=raw[2+2*i];
  }
  return true;
}
static unsigned finish_use(struct pd_field_input *a,pd_read_fn r,void *c) {
  uint8_t value,menu,running,queue;unsigned qty=0;
  if(a->useKind==USE_POTION && a->phase==USE_ITEM) {
    uint8_t count,mon[PD_PC_PARTY_SIZE];unsigned row;
    if(!read8(r,c,PD_PC_PARTY_COUNT,&count) || count>6 || a->healSlot>=count ||
       !r(c,PD_PC_PARTY_MON+a->healSlot*PD_PC_PARTY_SIZE,mon,sizeof mon) ||
       memcmp(mon,a->partyRecord,sizeof mon)) {a->result=PD_FIELD_REJECTED;return 0;}
    if(!menu_cursor(r,c,1,0,count+1,&row)){a->stable=0;return 0;}
    return pick_row(a, row, a->healSlot + 1, HEAL_EFFECT);
  }
  if(a->useKind==USE_POTION) {
    uint8_t mon[PD_PC_PARTY_SIZE];
    if(!r(c,PD_PC_PARTY_MON+a->healSlot*PD_PC_PARTY_SIZE,mon,sizeof mon) ||
       mon[0]!=a->partyRecord[0] || !remaining_quantity(a,r,c,&qty) ||
       (qty!=a->quantity && qty+1!=a->quantity)) {a->result=PD_FIELD_REJECTED;return 0;}
    unsigned hp=(mon[PD_PC_HP_OFFSET]<<8)|mon[PD_PC_HP_OFFSET+1];
    if(hp>a->beforeHP && qty+1==a->quantity)a->effectSeen=true;
    if(hp>a->beforeHP && qty==a->quantity)return PD_KEY_B;
  } else if(a->useKind==USE_BICYCLE) {
    unsigned slot;
    if(!key_slot(r,c,a->item,&slot) || !read8(r,c,PD_PC_PLAYER_STATE,&value)) {a->result=PD_FIELD_REJECTED;return 0;}
    if(value==(a->beforeState^1))a->effectSeen=true;
  } else {
    if(!read8(r,c,PD_PC_REPEL_STEPS,&value) || !remaining_quantity(a,r,c,&qty) ||
       (qty!=a->quantity && qty+1!=a->quantity)) {a->result=PD_FIELD_REJECTED;return 0;}
    if(value>0 && qty+1==a->quantity)a->effectSeen=true;
    /* UsedItemText waits for A/B before TossItem consumes the stack. Only
     * dismiss it after observing the Repel effect; never confirm another Use. */
    if(value>0 && qty==a->quantity) return PD_KEY_B;
  }
  if(!a->effectSeen) {
    if(a->frames - a->phaseFrame > 180)a->result=PD_FIELD_REJECTED;
    return 0;
  }
  bool free=field(r,c) && read8(r,c,PD_PC_IN_MENU,&menu) && !menu &&
    read8(r,c,PD_PC_SCRIPT_RUNNING,&running) && !running && read8(r,c,PD_PC_QUEUE,&queue) && !queue;
  if(free) {
    if(++a->stable>=6)a->result=PD_FIELD_DONE;
    return 0;
  }
  a->stable=0;
  return PD_KEY_B; /* Close text / Bag / Start only after the requested effect. */
}
static unsigned select_item(struct pd_field_input *a,pd_read_fn r,void *c,
                            unsigned menu,unsigned state,unsigned script) {
  if(!selected_stack(a,r,c)) {
    a->result=PD_FIELD_REJECTED;return 0;
  }
  if(!(state&0x40) || script!=1)return 0;
  unsigned char pocket,scroll,item;
  if(!read8(r,c,PD_PC_POCKET,&pocket) ||
     !read8(r,c,PD_PC_SCROLL,&scroll) || !read8(r,c,PD_PC_ITEM,&item)) {
    a->result=PD_FIELD_REJECTED;return 0;
  }
  if(a->phase==ITEM_MENU) {
    bool submenu=false;
    for(unsigned i=0;i<sizeof pd_pc_item_submenus/sizeof *pd_pc_item_submenus;i++)
      submenu=submenu || menu==pd_pc_item_submenus[i];
    if(a->item>0x200)submenu=menu==pd_pc_item_submenus[2] || menu==PD_PC_KEY_USE_SEL_MENU;
    unsigned row;
    if(submenu && item==(a->item&255) && menu_cursor(r, c, -1, -1, 0, &row)) {
      if(++a->stable>=6) {
        if(!a->useKind)a->result=PD_FIELD_DONE;
        else {
          bool useMenu=menu==pd_pc_item_submenus[0] || menu==pd_pc_item_submenus[2] ||
                       (a->useKind==USE_BICYCLE && menu==PD_PC_KEY_USE_SEL_MENU);
          if(!useMenu){a->result=PD_FIELD_REJECTED;return 0;}
          a->stable=0;
          if(row!=1)return PD_KEY_UP;
          advance(a, USE_ITEM);return PD_KEY_A;
        }
      }
      return 0;
    }
    if (submenu) {
      a->stable = 0;
      return 0;
    }
  }
  if(pocket>5){a->result=PD_FIELD_REJECTED;return 0;}
  uint8_t packState;
  if (!read8(r, c, PD_PC_PACK_STATE, &packState) || packState != 2 ||
      (pocket == 3 ? !tm_pocket_waiting(r, c) : menu != pd_pc_pack_menus[pocket < 3 ? pocket : pocket - 1])) {
    a->stable = 0;
    return 0;
  }
  if(++a->stable<4)return 0;
  a->stable=0;
  unsigned targetPocket=a->item>0x200 ? 5 : pd_pc_item_pockets[a->pocket].pocket;
  unsigned menuIndex=a->item>0x200 ? 4 : pd_pc_item_pockets[a->pocket].menu;
  if(pocket!=targetPocket)return PD_KEY_RIGHT;
  if(menu!=pd_pc_pack_menus[menuIndex])return 0;
  unsigned cursor;
  if(!menu_cursor(r, c, 2, 7, 0, &cursor) || cursor > 6)return 0;
  unsigned selected=scroll+cursor-1;
  if(selected==a->slot){advance(a, ITEM_MENU);return PD_KEY_A;}
  return selected<a->slot ? PD_KEY_DOWN : PD_KEY_UP;
}
unsigned pd_field_input_tick(struct pd_field_input *a, pd_read_fn r,
                            void *c) {
  if (a->result != PD_FIELD_RUNNING)
    return 0;
  // Long Pack lists need time to scroll. Every other phase must reach its next verified menu promptly.
  if (a->frames > 1800 || (a->phase != PICK_ITEM && a->frames - a->phaseFrame > 240)) {
    a->result = PD_FIELD_TIMEOUT;
    return 0;
  }
  if (a->phase == OPEN_START) {
    advance(a, PICK_START);
    return PD_KEY_START;
  }
  uint8_t ptr[2], state, script, battle;
  if (!r(c, PD_PC_POINTER, ptr, 2) || !read8(r, c, PD_PC_STATE, &state) ||
      !read8(r, c, PD_PC_SCRIPT, &script) || !read8(r, c, PD_PC_BATTLE, &battle)) {
    a->result = PD_FIELD_REJECTED;
    return 0;
  }
  unsigned menu = ptr[0] | (ptr[1] << 8);
  if (battle) {
    a->result = PD_FIELD_REJECTED;
    return 0;
  }
  const bool start = menu == PD_PC_START_MENU && (a->phase == PICK_START || a->phase == WAIT_BAG || a->phase == FLY_PARTY);
  if (!start && a->phase >= FLY_PARTY) return select_fly(a,r,c);
  if (a->phase == USE_ITEM || a->phase == HEAL_EFFECT) return finish_use(a,r,c);
  if (a->phase == PICK_ITEM || a->phase == ITEM_MENU) return select_item(a,r,c,menu,state,script);
  if (!start && a->phase == WAIT_BAG) {
    bool pack = false;
    uint8_t packState;
    for (unsigned i=0;i<sizeof pd_pc_pack_menus/sizeof *pd_pc_pack_menus;i++)
      pack = pack || menu == pd_pc_pack_menus[i];
    pack = pack || tm_pocket_waiting(r,c);
    if (pack && (state & 0x40) && script == 1 &&
        read8(r, c, PD_PC_PACK_STATE, &packState) && packState == 2) {
      if (++a->stable >= 6) {
        if(a->item){advance(a, PICK_ITEM);a->stable=0;}else a->result=PD_FIELD_DONE;
      }
    } else
      a->stable = 0;
    return 0;
  }
  uint8_t list[11];
  unsigned cursor;
  if (!start || !(state & 0x40) || script != 1) {
    a->stable = 0;
    return 0;
  }
  if (!r(c, PD_PC_LIST, list, sizeof list))
    return 0;
  unsigned n = list[0];
  if (n < 1 || n > 9 || list[n + 1] != 255 || !menu_cursor(r, c, -1, 11, n, &cursor)) {
    a->stable = 0;
    return 0;
  }
  unsigned seen = 0, row = 0;
  for (unsigned i = 1; i <= n; i++) {
    if (list[i] > 8 || (seen & (1u << list[i]))) {
      a->result = PD_FIELD_REJECTED;
      return 0;
    }
    seen |= 1u << list[i];
    if (list[i] == a->target)
      row = i;
  }
  if (!row) {
    a->result = PD_FIELD_REJECTED;
    return 0;
  }
  return pick_row(a, cursor, row, a->target == START_PARTY ? FLY_PARTY : WAIT_BAG);
}
void pd_field_input_cancel(struct pd_field_input *a) {
  if (a->result == PD_FIELD_RUNNING)
    a->result = PD_FIELD_CANCELLED;
}
const char *pd_field_input_message(const struct pd_field_input *a) {
  switch (a->result) {
  case PD_FIELD_DONE:
    if(a->useKind==USE_BICYCLE)return a->beforeState ? "Off bicycle" : "On bicycle";
    if(a->useKind==USE_REPEL)return "Repel used";
    if(a->useKind==USE_POTION)return "Pokemon healed";
    if(a->target==START_PARTY)return "Fly handed to game; choose a destination";
    return a->item ? "Selected item actions opened" : "Original game Bag opened";
  case PD_FIELD_REJECTED:
    if(a->useKind)return "Action unavailable or refused; game control restored";
    if(a->target==START_PARTY)return "Fly unavailable or party changed; use game controls";
    return "Item shortcut unavailable or inventory changed";
  case PD_FIELD_TIMEOUT:
    if(a->target==START_PARTY)return "Fly map did not open; game control restored";
    return "Field shortcut timed out; game control restored";
  case PD_FIELD_CANCELLED:
    return "Shortcut cancelled; game control restored";
  default:
    if(a->useKind==USE_BICYCLE)return a->beforeState ? "Getting off bicycle - B cancels" : "Getting on bicycle - B cancels";
    if(a->useKind==USE_REPEL)return "Using Repel - B cancels";
    if(a->useKind==USE_POTION)return "Healing Pokemon - B cancels";
    if(a->target==START_PARTY)return "Opening original Fly menu - B cancels";
    return "Opening original game Bag - B cancels";
  }
}
