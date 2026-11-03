#!/usr/bin/env python3
"""Co-op joined battles (pk_coop.c and the link bridge): retail English Emerald's addresses, code CRCs and struct offsets, from pret/pokeemerald
built byte for byte as retail (agbcc), written to app/src/main/cpp/pk_coop_emerald.h.

    scripts/gen_coop_emerald.py <pokeemerald dir>

The dir must hold a finished build (pokeemerald.gba + pokeemerald.elf, `make` after pret's INSTALL.md agbcc steps);
the header is refused unless pokeemerald.gba's SHA-1 is retail's (f3ae0881...). Every value comes from that build:

- addresses: `arm-none-eabi-nm pokeemerald.elf` (local `t` / `r` symbols too: several are static); functions even
  (no Thumb bit), as FieldItems.kt keeps them;
- COOP_EM_CRC_<function>: CRC32 of the first 32 ROM bytes at the function (pk_coop checks them, the bridge before patching);
- COOP_EM_OFF_* / constants: offsetof / sizeof / #defines compiled by agbcc against the decomp's own headers;
- the code sites a joined wild link battle patches (the foes' MULTI trainer picture, the escape roll's double test, ...), the
  link end's frontier test, the rewards' and the AI's: found by reading the named functions' Thumb code, each checked against
  the shape the compiler gave it, with the original bytes for an exact check and our replacement computed here;
- COOP_EM_LINK_*: the link bridge (plan 6 Task 1): scripts/coop_link_emerald.s assembled at COOP_EM_LINK_CODE_ADDR (its own 4 KB
  area, below the story lock's), and for each game function it replaces (LINK_HOOKS) the function's first 8 bytes as the file
  has them and as the head patch makes them (`ldr r3, [pc, #0]; bx r3; .word <entry | 1>`), its `link_*` absolute symbols as
  COOP_EM_ASM_LINK_* (pk_coop_link.c checks them against pk_coop.h);

Needs python3, cc (the C preprocessor, as pokeemerald's Makefile uses on Linux) and binutils-arm-none-eabi on PATH,
plus the build's own tools/agbcc: the decomp is cloned under $DECOMPS (see scripts/decomps.py), agbcc built and installed
into it (`./build.sh && ./install.sh ../pokeemerald`), then `make tools && make` in pokeemerald. There is no Dockerfile
for this in the repo: the run needs a Debian image (bookworm) with build-essential, binutils-arm-none-eabi, git,
libpng-dev and python3, whose entrypoint is /bin/bash. Example,
with the image built from those packages and tagged pokedaisy-decomp:

    MSYS_NO_PATHCONV=1 docker run --rm -v "$DECOMPS:/decomps" -v "<this repo>:/repo" pokedaisy-decomp \
        -c "python3 /repo/scripts/gen_coop_emerald.py /decomps/pokeemerald"
"""
import hashlib
import os
import re
import struct
import subprocess
import sys
import tempfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_H = os.path.join(HERE, "..", "app", "src", "main", "cpp", "pk_coop_emerald.h")
ASM_LINK = os.path.join(HERE, "coop_link_emerald.s")
# The link bridge's code (plan 6): its own 4 KB below the story lock's (0x08FFE000) and co-op's (0x08FFF000).
LINK_CODE_ADDR = 0x08FFD000
LINK_CODE_MAX = 0x1000
# Game functions the bridge replaces by a head patch (function, entry label suffix): link.c's block layer without the cable.
LINK_HOOKS = [("SendBlock", "SENDBLOCK"), ("IsLinkTaskFinished", "ISLINKTASKFINISHED"), ("GetMultiplayerId", "GETMULTIPLAYERID"),
              ("HandleLinkConnection", "HANDLELINKCONNECTION"), ("OpenLink", "OPENLINK"), ("CloseLink", "CLOSELINK"),
              ("SetCloseLinkCallback", "CLOSELINK"),
              ("HasLinkErrorOccurred", "HASLINKERROROCCURRED"),
              # task 2: a joined trainer battle shows the real route trainers (names, pictures, classes, no speeches, their own levels)
              ("GetFrontierTrainerName", "GETFRONTIERTRAINERNAME"), ("GetFrontierTrainerFrontSpriteId", "GETFRONTIERTRAINERFRONTSPRITEID"),
              ("GetFrontierOpponentClass", "GETFRONTIEROPPONENTCLASS"), ("GetBattleTowerTrainerLanguage", "GETBATTLETOWERTRAINERLANGUAGE"),
              ("CopyFrontierTrainerText", "COPYFRONTIERTRAINERTEXT"),
              ("TrySetLinkBattleTowerEnemyPartyLevel", "TRYSETLINKBATTLETOWERENEMYPARTYLEVEL"),
              ("FindLinkBattleMaster", "FINDLINKBATTLEMASTER"),   # task 3
              # task 4 (pk_coop_link_rewards.c's own head patches)
              ("ItemUseInBattle_PokeBall", "BALL"), ("CompleteWhenChoseItem", "CHOSE_ITEM"),
              ("Cmd_trysetcaughtmondexflags", "CATCH_DEX"), ("Cmd_givecaughtmon", "CATCH_GIVE")]
RETAIL_SHA1 = "f3ae088181bf583e55daf962a92bb46f4f1d07b7"
ROM_BASE = 0x08000000
CRC_BYTES = 32

# (define name, ELF symbol): pret renamed some since the plan was written. GetMonData is a macro over
# GetMonData2 (two arguments) / GetMonData3 now: GetMonData2 is the function FieldItems calls GetMonData.
FUNCTIONS = [
    ("BtlController_EmitTwoReturnValues", "BtlController_EmitTwoReturnValues"),
    ("BtlController_EmitOneReturnValue", "BtlController_EmitOneReturnValue"),
    ("Cmd_getexp", "Cmd_getexp"),
    ("Cmd_givecaughtmon", "Cmd_givecaughtmon"),
    ("Cmd_trysetcaughtmondexflags", "Cmd_trysetcaughtmondexflags"),
    ("ItemUseInBattle_PokeBall", "ItemUseInBattle_PokeBall"),
    ("TryGenerateWildMon", "TryGenerateWildMon"),
    ("GetCurrentMapWildMonHeaderId", "GetCurrentMapWildMonHeaderId"),
    ("Task_BattleStart", "Task_BattleStart"),
    ("ExecuteTableBasedItemEffect", "ExecuteTableBasedItemEffect"),
    ("GetMonData", "GetMonData2"),
    ("DisplayItemMessage", "DisplayItemMessage"),
    ("CloseItemMessage", "CloseItemMessage"),
    ("ScriptContext_SetupScript", "ScriptContext_SetupScript"),
    ("CB2_Overworld", "CB2_Overworld"),
    ("CB2_WhiteOut", "CB2_WhiteOut"),
    ("CB2_EvolutionSceneUpdate", "CB2_EvolutionSceneUpdate"),
    # The functions holding the code sites below (patched there, so CRC-checked like the rest).
    # pk_coop_link_begin's per-battle sites: CB2_InitBattle's party showcase literal (pointed at CB2_InitBattleInternal) and
    # InitSinglePlayerBtlControllers' starting party indexes.
    ("CB2_InitBattle", "CB2_InitBattle"),
    ("CB2_InitBattleInternal", "CB2_InitBattleInternal"),
    ("BattleIntroDrawTrainersOrMonsSprites", "BattleIntroDrawTrainersOrMonsSprites"),
    ("TryRunFromBattle", "TryRunFromBattle"),
    # Task 6 (full EXP): called by our getexp hook.
    ("GetItemHoldEffect", "GetItemHoldEffect"),
    # Task 8: the partner's items - the battle-use functions gItems points at (compared, not called) and the healthbox
    # refresh after a medicine.
    ("ItemUseInBattle_Medicine", "ItemUseInBattle_Medicine"),
    ("ItemUseInBattle_StatIncrease", "ItemUseInBattle_StatIncrease"),
    # Plan 4 (joined battles): the after-battle callbacks, the START menu, field lock, party / bag / dex / evolution
    # and save-path functions the native tasks 7-9 call or watch.
    ("CB2_EndWildBattle", "CB2_EndWildBattle"),
    ("CB2_EndTrainerBattle", "CB2_EndTrainerBattle"),
    ("CB2_EndScriptedWildBattle", "CB2_EndScriptedWildBattle"),
    ("HandleStartMenuInput", "HandleStartMenuInput"),
    ("ArePlayerFieldControlsLocked", "ArePlayerFieldControlsLocked"),
    ("GiveMonToPlayer", "GiveMonToPlayer"),
    ("GetSetPokedexFlag", "GetSetPokedexFlag"),
    ("SpeciesToNationalPokedexNum", "SpeciesToNationalPokedexNum"),
    ("RemoveBagItem", "RemoveBagItem"),
    ("CheckBagHasItem", "CheckBagHasItem"),
    ("FlagSet", "FlagSet"),
    ("AddMoney", "AddMoney"),
    ("GetEvolutionTargetSpecies", "GetEvolutionTargetSpecies"),
    ("BeginEvolutionScene", "BeginEvolutionScene"),
    ("CB2_ReturnToField", "CB2_ReturnToField"),
    ("SetMainCallback2", "SetMainCallback2"),
    # The START menu's save path: SaveDoSaveCallback calls IncrementGameStat, PausePyramidChallenge, TrySavingData.
    ("SaveDoSaveCallback", "SaveDoSaveCallback"),
    ("IncrementGameStat", "IncrementGameStat"),
    ("PausePyramidChallenge", "PausePyramidChallenge"),
    ("TrySavingData", "TrySavingData"),
    # Plan 6 (joined battles as link battles): link.c's block layer (replaced by the bridge's stubs while a link battle runs),
    # the pieces pk_coop_link_begin calls, and the tower multi's start.
    ("SendBlock", "SendBlock"),
    ("IsLinkTaskFinished", "IsLinkTaskFinished"),
    ("GetMultiplayerId", "GetMultiplayerId"),
    ("HandleLinkConnection", "HandleLinkConnection"),
    ("OpenLink", "OpenLink"),
    ("CloseLink", "CloseLink"),
    ("HasLinkErrorOccurred", "HasLinkErrorOccurred"),
    ("CreateNPCTrainerParty", "CreateNPCTrainerParty"),
    ("CleanupOverworldWindowsAndTilemaps", "CleanupOverworldWindowsAndTilemaps"),
    ("CB2_HandleStartMultiPartnerBattle", "CB2_HandleStartMultiPartnerBattle"),
    ("SaveLinkPlayers", "SaveLinkPlayers"),
    # task 3 (wild as a link battle): the functions holding its per-battle code sites, and the music call.
    ("IsRunningFromBattleImpossible", "IsRunningFromBattleImpossible"),
    ("HandleAction_Run", "HandleAction_Run"),
    ("TrySetLinkBattleTowerEnemyPartyLevel", "TrySetLinkBattleTowerEnemyPartyLevel"),
    ("PlayBattleBGM", "PlayBattleBGM"),
    ("FindLinkBattleMaster", "FindLinkBattleMaster"),
    ("InitLinkBattleVsScreen", "InitLinkBattleVsScreen"),
    ("DestroyTask", "DestroyTask"),
    ("Cmd_endlinkbattle", "Cmd_endlinkbattle"),
    ("HandleEndTurn_FinishBattle", "HandleEndTurn_FinishBattle"),
    ("HandleEndTurn_RanFromBattle", "HandleEndTurn_RanFromBattle"),
    ("HandleEndTurn_MonFled", "HandleEndTurn_MonFled"),
    ("SetCloseLinkCallback", "SetCloseLinkCallback"),
    # task 2: the tower flow's trainer lookups, which a joined trainer battle answers from gTrainers instead
    ("GetFrontierTrainerName", "GetFrontierTrainerName"),
    ("GetFrontierTrainerFrontSpriteId", "GetFrontierTrainerFrontSpriteId"),
    ("GetFrontierOpponentClass", "GetFrontierOpponentClass"),
    ("GetBattleTowerTrainerLanguage", "GetBattleTowerTrainerLanguage"),
    ("CopyFrontierTrainerText", "CopyFrontierTrainerText"),
    ("TrySetLinkBattleTowerEnemyPartyLevel", "TrySetLinkBattleTowerEnemyPartyLevel"),
    ("TaskDummy", "TaskDummy"),   # the idle task function ResetTasks puts in every slot (pk_coop_link_start.c)
    ("BattleAI_SetupAIData", "BattleAI_SetupAIData"),   # task 2: the AI flag choice, patched while a link battle runs
    # task 7 (a dropped link): the battle's own main loop pieces pk_coop_link_leave points back at the normal ending
    ("BattleMainCB2", "BattleMainCB2"),
    ("BattleMainCB1", "BattleMainCB1"),
    ("BattleControllerDummy", "BattleControllerDummy"),
    ("CB2_ReturnToFieldLocal", "CB2_ReturnToFieldLocal"),
    ("CB2_ReturnToFieldContinueScriptPlayMapMusic", "CB2_ReturnToFieldContinueScriptPlayMapMusic"),
    # task 4 (EXP, the BAG and catching in a link battle): replaced / wrapped while a link battle runs, and what the wrappers call
    ("Cmd_handlelearnnewmove", "Cmd_handlelearnnewmove"),
    ("HandleAction_UseItem", "HandleAction_UseItem"),
    ("CompleteWhenChoseItem", "CompleteWhenChoseItem"),
    ("HandleTurnActionSelectionState", "HandleTurnActionSelectionState"),
    ("SetMonData", "SetMonData"),
    ("CalculateMonStats", "CalculateMonStats"),
    ("PlayerBufferExecCompleted", "PlayerBufferExecCompleted"),
    ("IsPlayerPartyAndPokemonStorageFull", "IsPlayerPartyAndPokemonStorageFull"),
    ("Task_FadeAndCloseBagMenu", "Task_FadeAndCloseBagMenu"),
    ("GetPartyIdFromBattleSlot", "GetPartyIdFromBattleSlot"),
    ("Cmd_handleballthrow", "Cmd_handleballthrow"),
    ("Cmd_drawlvlupbox", "Cmd_drawlvlupbox"),
    ("ItemUseInBattle_PPRecovery", "ItemUseInBattle_PPRecovery"),
    # task 5 (the end of a joined link battle)
    ("CB2_InitEndLinkBattle", "CB2_InitEndLinkBattle"),
    ("ScriptContext_Init", "ScriptContext_Init"),
    ("UnlockPlayerFieldControls", "UnlockPlayerFieldControls"),
    ("ScriptUnfreezeObjectEvents", "ScriptUnfreezeObjectEvents"),
]
# Symbols the build has twice (two controllers' static handlers of one name): the lowest address is the player's.
LOWEST_SYMBOLS = ["HandleInputChooseAction"]
ROM_DATA = ["gBattleScriptingCommandsTable", "gItems", "gWildMonHeaders", "gTrainers",
            "gSpeciesInfo", "gBattleMoves", "gTrainerMoneyTable",
            # task 4
            "gExperienceTables", "sTurnActionsFuncsTable", "gText_BoxFull", "gCaughtMonStringIds"]
RAM = ["gMain", "gTasks", "gBattleTypeFlags", "gPlayerParty", "gPlayerPartyCount", "gEnemyParty",
       "gBattleOutcome", "gLeveledUpInBattle", "gBattlerAttacker", "gBattlerTarget", "gActiveBattler", "gBattleBufferA",
       "gBattleMons", "gBattlerPartyIndexes", "gAbsentBattlerFlags", "gBattleStruct", "gExpShareExp",
       "gPlayerAvatar",
       # Task 6 (full EXP)
       "gBattleControllerExecFlags", "gBattlerFainted", "gBattleMoveDamage", "gSaveBlock1Ptr",
       "gBattlescriptCurrInstr", "gBattlerInMenuId",
       # Plan 4: the trainer pair, the START menu's callback (pret's name: gMenuCallback), the palette fade, the
       # field-lock byte ArePlayerFieldControlsLocked returns (static in script.c), the evolution scene's return
       "gTrainerBattleOpponent_A", "gTrainerBattleOpponent_B", "gMenuCallback", "gPaletteFade", "sLockFieldControls",
       "gCB2_AfterEvolution",
       # Plan 6: the link layer's variables, the link battle's buffers and the player blocks
       "gLinkStatus", "gReceivedRemoteLinkPlayers", "gBlockReceivedStatus", "gBlockRecvBuffer", "gLinkPlayers",
       "gLocalLinkPlayer", "gLinkType", "gLinkBattleSendBuffer", "gLinkBattleRecvBuffer", "gRecordedBattleRngSeed",
       "gSaveBlock2Ptr", "gSavedLinkPlayerCount", "sMultiPartnerPartyBuffer", "gBattleCommunication", "gBattlerControllerFuncs", "gStringVar4", "sTransitionData",   # task 2
       # task 7 (a dropped link)
       "gBattleMainFunc", "sTextPrinters", "sHeapStart",
       # task 4
       "gBattleBufferB", "gBattleScripting", "gSpecialVar_ItemId", "gPartyMenu", "gLastUsedItem",
       "gBattlerTarget", "gChosenActionByBattler",
       # task 5
       "sGlobalScriptContextStatus"]
# Battle scripts (data/battle_scripts_1.s labels: byte-aligned ROM data, `D` to nm) the bridge's code jumps to: a partner's catch goes
# straight to the first one.
BATTLE_SCRIPTS = ["BattleScript_GiveCaughtMonEnd"]
EMIT_SCRIPTS = BATTLE_SCRIPTS

# (define name, C expression), compiled by agbcc against the decomp's headers.
OFFSETS = [
    ("OFF_TASK_SIZE", "sizeof(struct Task)"),
    ("OFF_TASK_FUNC", "offsetof(struct Task, func)"),
    ("OFF_TASK_IS_ACTIVE", "offsetof(struct Task, isActive)"),
    ("OFF_BATTLE_STRUCT_EXP_GETTER_MON_ID", "offsetof(struct BattleStruct, expGetterMonId)"),
    ("OFF_TRAINER_NAME", "offsetof(struct Trainer, trainerName)"),
    ("OFF_TRAINER_SIZE", "sizeof(struct Trainer)"),
    ("OFF_ITEM_BATTLE_USE_FUNC", "offsetof(struct Item, battleUseFunc)"),
    ("OFF_ITEM_SIZE", "sizeof(struct Item)"),
    ("OFF_BATTLE_MON_SIZE", "sizeof(struct BattlePokemon)"),
    ("OFF_BATTLE_MON_MOVES", "offsetof(struct BattlePokemon, moves)"),
    ("OFF_BATTLE_MON_PP", "offsetof(struct BattlePokemon, pp)"),
    ("OFF_BATTLE_MON_HP", "offsetof(struct BattlePokemon, hp)"),
    ("OFF_MON_SIZE", "sizeof(struct Pokemon)"),
    ("OFF_MON_HP", "offsetof(struct Pokemon, hp)"),
    # The bit-field byte right after .language: isBadEgg (bit 0), hasSpecies (bit 1), isEgg (bit 2).
    ("OFF_BOX_MON_FLAGS", "offsetof(struct BoxPokemon, language) + 1"),
    ("OFF_BATTLE_MOVE_SIZE", "sizeof(struct BattleMove)"),
    # Task 5: the second wild Pokemon (pk_coop.c coop_second_wild).
    ("OFF_PLAYER_AVATAR_FLAGS", "offsetof(struct PlayerAvatar, flags)"),
    ("OFF_WILD_HEADER_SIZE", "sizeof(struct WildPokemonHeader)"),
    ("OFF_WILD_HEADER_LAND", "offsetof(struct WildPokemonHeader, landMonsInfo)"),
    ("OFF_WILD_HEADER_WATER", "offsetof(struct WildPokemonHeader, waterMonsInfo)"),
    # Task 6 (full EXP)
    ("OFF_SPECIES_INFO_SIZE", "sizeof(struct SpeciesInfo)"),
    ("OFF_SPECIES_INFO_EXP_YIELD", "offsetof(struct SpeciesInfo, expYield)"),
    ("OFF_BATTLE_MON_SPECIES", "offsetof(struct BattlePokemon, species)"),
    ("OFF_BATTLE_MON_LEVEL", "offsetof(struct BattlePokemon, level)"),
    ("OFF_BATTLE_STRUCT_EXP_VALUE", "offsetof(struct BattleStruct, expValue)"),
    ("OFF_SAVEBLOCK1_ENIGMA_HOLD_EFFECT", "offsetof(struct SaveBlock1, enigmaBerry.holdEffect)"),
    ("OFF_BOX_MON_OT_ID", "offsetof(struct BoxPokemon, otId)"),
    # Plan 4 (joined battles)
    ("OFF_MAIN_SAVED_CALLBACK", "offsetof(struct Main, savedCallback)"),
    ("OFF_SB1_MONEY", "offsetof(struct SaveBlock1, money)"),
    ("OFF_TRAINER_PARTY_FLAGS", "offsetof(struct Trainer, partyFlags)"),
    ("OFF_TRAINER_CLASS", "offsetof(struct Trainer, trainerClass)"),
    ("OFF_TRAINER_PARTY_SIZE", "offsetof(struct Trainer, partySize)"),
    ("OFF_TRAINER_DOUBLE_BATTLE", "offsetof(struct Trainer, doubleBattle)"),
    ("OFF_MON_LEVEL", "offsetof(struct Pokemon, level)"),
    ("OFF_TRAINER_PARTY", "offsetof(struct Trainer, party)"),
    ("OFF_TRAINER_MONEY_SIZE", "sizeof(struct TrainerMoney)"),
    # Plan 6: struct LinkPlayer, the player's own fields in SaveBlock2, struct Main, the link battle header
    ("OFF_LINK_PLAYER_SIZE", "sizeof(struct LinkPlayer)"),
    ("OFF_LP_VERSION", "offsetof(struct LinkPlayer, version)"),
    ("OFF_LP_FIELD_2", "offsetof(struct LinkPlayer, lp_field_2)"),
    ("OFF_LP_TRAINER_ID", "offsetof(struct LinkPlayer, trainerId)"),
    ("OFF_LP_NAME", "offsetof(struct LinkPlayer, name)"),
    ("OFF_LP_GENDER", "offsetof(struct LinkPlayer, gender)"),
    ("OFF_LP_LINK_TYPE", "offsetof(struct LinkPlayer, linkType)"),
    ("OFF_LP_LANGUAGE", "offsetof(struct LinkPlayer, language)"),
    ("OFF_SB2_PLAYER_NAME", "offsetof(struct SaveBlock2, playerName)"),
    ("OFF_SB2_PLAYER_GENDER", "offsetof(struct SaveBlock2, playerGender)"),
    ("OFF_SB2_PLAYER_TRAINER_ID", "offsetof(struct SaveBlock2, playerTrainerId)"),
    ("OFF_MAIN_CALLBACK2", "offsetof(struct Main, callback2)"),
    ("OFF_BATTLE_STRUCT_MULTIBUFFER", "offsetof(struct BattleStruct, multiBuffer)"),
    ("LINK_BATTLER_HEADER_SIZE", "sizeof(struct LinkBattlerHeader)"),
    ("OFF_MULTI_PARTNER_MON_SIZE", "sizeof(struct MultiPartnerMenuPokemon)"),
    ("OFF_BATTLE_MOVE_PP", "offsetof(struct BattleMove, pp)"),
    ("OFF_TRAINER_PIC", "offsetof(struct Trainer, trainerPic)"),   # task 2
    # task 7
    ("OFF_TEXT_PRINTER_ACTIVE", "offsetof(struct TextPrinter, active)"),
    ("TEXT_PRINTER_SIZE", "sizeof(struct TextPrinter)"),
    # task 4
    ("OFF_SPECIES_INFO_GROWTH_RATE", "offsetof(struct SpeciesInfo, growthRate)"),
    ("OFF_BATTLE_SCRIPTING_GETEXP_STATE", "offsetof(struct BattleScripting, getexpState)"),
    ("OFF_BATTLE_STRUCT_SENT_IN_POKES", "offsetof(struct BattleStruct, sentInPokes)"),
    ("OFF_PARTY_MENU_SLOT_ID", "offsetof(struct PartyMenu, slotId)"),
    ("OFF_BATTLE_MON_STATUS1", "offsetof(struct BattlePokemon, status1)"),
    ("OFF_MAIN_NEW_KEYS", "offsetof(struct Main, newKeys)"),
    ("OFF_BATTLE_BUFFER_B_STRIDE", "sizeof(gBattleBufferB[0])"),
    # (the bag, for native-capture/mgba_dump.c's `bagqty`: every pocket lies between these two)
    ("OFF_SB1_BAG_START", "offsetof(struct SaveBlock1, bagPocket_Items)"),
    ("OFF_SB1_BAG_END", "offsetof(struct SaveBlock1, bagPocket_Berries) + sizeof(((struct SaveBlock1 *)0)->bagPocket_Berries)"),
    ("OFF_SB2_ENCRYPTION_KEY", "offsetof(struct SaveBlock2, encryptionKey)"),
]
CONSTANTS = ["B_COMM_TO_ENGINE", "B_ACTION_EXEC_SCRIPT", "B_POSITION_PLAYER_RIGHT", "PLAYER_NAME_LENGTH", "PARTY_SIZE", "BATTLE_TYPE_DOUBLE", "BATTLE_TYPE_TRAINER", "BATTLE_TYPE_MULTI",
             "BATTLE_TYPE_TWO_OPPONENTS", "BATTLE_TYPE_INGAME_PARTNER",
             "PLAYER_AVATAR_FLAG_SURFING",
             # EXP, the bag and catching (the rewards)
             "ITEM_ENIGMA_BERRY", "HOLD_EFFECT_EXP_SHARE", "MON_DATA_SPECIES", "MON_DATA_HP", "MON_DATA_HELD_ITEM", "MON_DATA_EXP",
             "FONT_NORMAL", "LAST_BALL", "ITEMS_COUNT",
             # Plan 6 (link battles)
             "MAX_LINK_PLAYERS", "BLOCK_BUFFER_SIZE", "LINKTYPE_BATTLE", "VERSION_EMERALD", "LANGUAGE_ENGLISH",
             "BATTLE_TYPE_LINK", "BATTLE_TYPE_IS_MASTER", "BATTLE_TYPE_LINK_IN_BATTLE", "BATTLE_TYPE_BATTLE_TOWER",
             "B_COMM_TO_CONTROLLER", "B_COMM_TO_ENGINE", "B_COMM_CONTROLLER_IS_DONE", "CONTROLLER_CMDS_COUNT",
             "BATTLE_BUFFER_LINK_SIZE", "BIT_SIDE", "MULTI_PARTY_SIZE", "SPECIES_EGG", "MOVES_COUNT", "ITEM_AMULET_COIN",
             "CONTROLLER_TWORETURNVALUES", "CONTROLLER_CHOSENMONRETURNVALUE", "CONTROLLER_ONERETURNVALUE",
             "CONTROLLER_ONERETURNVALUE_DUPLICATE", "CONTROLLER_DATATRANSFER",
             "TRAINERS_COUNT", "BATTLE_TYPE_FRONTIER", "BATTLE_TYPE_EREADER_TRAINER", "BATTLE_TYPE_TRAINER_HILL", "BATTLE_TYPE_SECRET_BASE",   # task 2
             # task 7 (a dropped link)
             "WINDOWS_MAX", "NUM_TASKS", "NUM_TASK_DATA", "HEAD_SENTINEL", "TAIL_SENTINEL", "B_OUTCOME_LOST",
             "B_OUTCOME_PLAYER_TELEPORTED", "BATTLE_TYPE_RECORDED_LINK",
             # task 4
             "MAX_LEVEL", "RET_VALUE_LEVELED_UP", "B_SCR_OP_GETEXP", "B_SCR_OP_HANDLELEARNNEWMOVE", "B_ACTION_USE_ITEM",
             "B_SCR_OP_PRINTFROMTABLE", "B_SCR_OP_WAITMESSAGE", "B_SCR_OP_SETBYTE", "B_SCR_OP_ENDLINKBATTLE",
             "B_SCR_OP_FINISHTURN", "B_SCR_OP_FINISHACTION", "B_SCR_OP_HANDLEBALLTHROW", "B_SCR_OP_DRAWLVLUPBOX", "A_BUTTON", "B_OUTCOME_CAUGHT", "B_WAIT_TIME_LONG",
             "MON_DATA_LEVEL", "MON_DATA_STATUS", "BATTLE_TYPE_SAFARI", "BATTLE_TYPE_FRONTIER_NO_PYRAMID"]
PROBE_HEADERS = ["global.h", "task.h", "battle.h", "battle_controllers.h", "data.h", "item.h", "sprite.h", "pokemon.h",
                 "constants/trainers.h", "constants/opponents.h", "constants/party_menu.h", "constants/items.h",
                 "constants/hold_effects.h", "constants/battle_string_ids.h", "wild_encounter.h", "text.h",
                 "constants/battle_script_commands.h", "battle_interface.h", "battle_main.h", "main.h", "link.h", "window.h", "party_menu.h"]


# task 3: the escape's end (our RAN_FINISH stub reads these)
ROM_DATA += ["sEndTurnFuncsTable"]
RAM += ["gCurrentActionFuncId"]
CONSTANTS += ["B_ACTION_TRY_FINISH", "B_ACTION_FINISHED"]
# --- Task 6 (story battles as link battles, pk_coop_link_story.c): the story trainer start's hook, the pick screen's pieces ---
FUNCTIONS += [("BattleSetup_StartTrainerBattle", "BattleSetup_StartTrainerBattle"),
              ("ScriptContext_Stop", "ScriptContext_Stop"),
              ("ChooseHalfPartyForBattle", "ChooseHalfPartyForBattle")]
RAM += ["sGlobalScriptContext", "gSelectedOrderFromParty", "gSpecialVar_Result", "gNoOfApproachingTrainers",
        "sNoOfPossibleTrainerRetScripts", "sShouldCheckTrainerBScript", "gWhichTrainerToFaceAfterBattle"]
OFFSETS += [("OFF_SCRIPT_CONTEXT_SCRIPT_PTR", "offsetof(struct ScriptContext, scriptPtr)")]
PROBE_HEADERS += ["script.h", "constants/game_stat.h", "constants/vars.h"]
OFFSETS += [("OFF_SB1_VARS", "offsetof(struct SaveBlock1, vars)")]
CONSTANTS += ["GAME_STAT_TOTAL_BATTLES", "GAME_STAT_TRAINER_BATTLES", "VAR_FRONTIER_FACILITY", "VARS_START"]
# head-patched while a story battle is armed (the patch goes in at arm and out at go / cancel, never left in)
STORY_HOOKS = [("BattleSetup_StartTrainerBattle", "STORYSTART")]
# script commands (data/script_cmd_table.inc) the pick script is made of
STORY_SCRIPT_CMDS = [("SCR_OP_SPECIAL", "ScrCmd_special"), ("SCR_OP_WAITSTATE", "ScrCmd_waitstate"),
                     ("SCR_OP_DOTRAINERBATTLE", "ScrCmd_dotrainerbattle"), ("SCR_OP_GOTO", "ScrCmd_goto")]


def story_constants(decomp, syms, rom):
    """{name: value}: the opcode of each STORY_SCRIPT_CMDS command and `special ChooseHalfPartyForBattle`'s index, both read from the
    decomp's data files and the second checked against gSpecials in the built ROM."""
    out = {}
    with open(os.path.join(decomp, "data", "script_cmd_table.inc")) as f:
        table = f.read()
    for name, fn in STORY_SCRIPT_CMDS:
        m = re.search(r"\b%s\s+@ 0x([0-9A-Fa-f]+)" % fn, table)
        if not m:
            die("no %s in data/script_cmd_table.inc" % fn)
        out[name] = int(m.group(1), 16)
    index = 0
    with open(os.path.join(decomp, "data", "specials.inc")) as f:
        for line in f:
            m = re.match(r"\s+def_special\s+(\w+)", line)
            if m:
                if m.group(1) == "ChooseHalfPartyForBattle":
                    break
                index += 1
        else:
            die("no def_special ChooseHalfPartyForBattle in data/specials.inc")
    table_at = symbol(syms, "gSpecials", "Dd") - ROM_BASE
    fn = symbol(syms, "ChooseHalfPartyForBattle", "Tt") & ~1
    if struct.unpack_from("<I", rom, table_at + 4 * index)[0] & ~1 != fn:
        die("gSpecials[%d] isn't ChooseHalfPartyForBattle" % index)
    out["SPECIAL_CHOOSE_HALF_PARTY"] = index
    return out


def die(msg):
    sys.exit("gen_coop_emerald: " + msg)


def read_symbols(elf):
    """name -> [(address, nm type)], every symbol nm lists."""
    out = subprocess.run(["arm-none-eabi-nm", elf], check=True, capture_output=True, text=True).stdout
    syms = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 3:
            syms.setdefault(parts[2], []).append((int(parts[0], 16), parts[1]))
    return syms


def symbol(syms, name, types):
    found = [(a, t) for a, t in syms.get(name, []) if t in types]
    if len(found) != 1:
        die("%s: expected one symbol of type %s, nm has %s" % (name, types, syms.get(name)))
    return found[0][0]


def probe_values(decomp, exprs):
    """Compiles `const int coop_probe[] = { exprs }` with agbcc (the Makefile's preprocessor flags) and reads the words."""
    src = "".join('#include "%s"\n' % h for h in PROBE_HEADERS)
    src += "const int coop_probe[] = {\n" + "".join("    %s,\n" % e for e in exprs) + "};\n"
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "coop_probe.c")
        with open(path, "w") as f:
            f.write(src)
        pre = subprocess.run(["cc", "-E", "-iquote", "include", "-Wno-trigraphs", "-DMODERN=0",
                              "-I", "tools/agbcc/include", "-I", "tools/agbcc", "-nostdinc", "-undef", "-std=gnu89",
                              path], cwd=decomp, check=True, capture_output=True, text=True).stdout
        asm = subprocess.run([os.path.join(decomp, "tools", "agbcc", "bin", "agbcc"), "-mthumb-interwork", "-O2",
                              "-fhex-asm", "-o", "-", "-"], cwd=decomp, input=pre, check=True, capture_output=True,
                             text=True).stdout
    words, inside = [], False
    for line in asm.splitlines():
        line = line.strip()
        if line.startswith("coop_probe:"):
            inside = True
        elif inside:
            m = re.match(r"\.(?:word|4byte|long)\s+(\S+)", line)
            if not m:
                break
            words.append(int(m.group(1), 0))
    if len(words) != len(exprs):
        die("probe gave %d words for %d expressions:\n%s" % (len(words), len(exprs), asm))
    return words


class Thumb:
    """Just enough Thumb decoding to find the code sites, in a function's [start, end)."""

    def __init__(self, rom, start, end):
        self.rom, self.start, self.end = rom, start, end

    def hw(self, addr):
        return struct.unpack_from("<H", self.rom, addr - ROM_BASE)[0]

    def word(self, addr):
        return struct.unpack_from("<I", self.rom, addr - ROM_BASE)[0]

    def literal_loads(self, value):
        """[(address, rd)] of every `ldr rd, [pc, #n]` loading `value` (pools sit inside the range or just past it)."""
        out = []
        for a in range(self.start, self.end, 2):
            h = self.hw(a)
            if h & 0xF800 == 0x4800:
                lit = ((a + 4) & ~3) + (h & 0xFF) * 4
                if lit < self.end + 0x400 and self.word(lit) == value:
                    out.append((a, (h >> 8) & 7))
        return out


def find_arm_sites(rom, ranges, k, addr_of):
    """The two code sites a joined WILD link battle patches (pk_coop_link_wild.c), each as (address, [(suffix, value, digits)],
    comment): the file's bytes (to check) and ours, computed and checked here so the C only copies them."""
    out = {}

    # BattleIntroDrawTrainersOrMonsSprites: in a MULTI battle the battlers at B_POSITION_PLAYER_RIGHT and
    # B_POSITION_OPPONENT_RIGHT draw a trainer picture - in a wild battle a trainer would stand where the second wild
    # Pokemon is. The `cmp r0, #B_POSITION_OPPONENT_RIGHT` after `cmp r0, #B_POSITION_PLAYER_RIGHT; beq` (the MULTI
    # test's, the first such pair) becomes `cmp r0, #0xFF`, a position no battler has.
    code = Thumb(rom, *ranges["BattleIntroDrawTrainersOrMonsSprites"])
    found = []
    for a in range(code.start, code.end - 16, 2):
        if code.hw(a) == 0x2802 and code.hw(a + 2) & 0xFF00 == 0xD000 and code.hw(a + 14) == 0x2803 \
                and code.hw(a + 16) & 0xFF00 == 0xD100 and code.hw(a + 6) & 0xF800 == 0xF000:
            found.append(a + 14)
    if len(found) != 1:
        die("BattleIntroDrawTrainersOrMonsSprites: expected one PLAYER_RIGHT / OPPONENT_RIGHT test, found %d"
            % len(found))
    if not any(code.hw(a) == 0x2140 for a in range(found[0] - 40, found[0] - 14, 2)):
        die("0x%08X: no BATTLE_TYPE_MULTI test before the position test" % found[0])
    out["INTRO_FOE_RIGHT_CMP"] = (found[0], [("INSN", 0x2803, 4), ("NEW", 0x28FF, 4)],
                                  "cmp r0, #B_POSITION_OPPONENT_RIGHT (the MULTI trainer picture; _NEW = cmp r0, #0xFF)")

    # TryRunFromBattle: the escape roll (speed vs the foe's, runTries) sits inside `if (!(gBattleTypeFlags &
    # BATTLE_TYPE_DOUBLE))`, so in any double battle - a joined wild one included - only RUN AWAY / a Smoke Ball gets out.
    # Compiled as `ldr r0, [r1]; movs r1, #1; ands r0, r1; cmp r0, #0; bne <the runTries++>`; the bne (BATTLE_TYPE_DOUBLE
    # is bit 0) becomes a nop for a joined WILD battle, so the runner takes the single battle's roll against the battler
    # opposite it (battler ^ 1: the host against the foe at 1, the partner against the foe at 3).
    code = Thumb(rom, *ranges["TryRunFromBattle"])
    if k["BATTLE_TYPE_DOUBLE"] != 1:
        die("BATTLE_TYPE_DOUBLE is no longer bit 0")
    found = []
    for a in range(code.start, code.end - 8, 2):
        if code.hw(a) == 0x6808 and code.hw(a + 2) == 0x2101 and code.hw(a + 4) == 0x4008 \
                and code.hw(a + 6) == 0x2800 and code.hw(a + 8) & 0xFF00 == 0xD100:
            found.append(a + 8)
    if len(found) != 1:
        die("TryRunFromBattle: expected one BATTLE_TYPE_DOUBLE test, found %d" % len(found))
    out["RUN_DOUBLE_BNE"] = (found[0], [("INSN", code.hw(found[0]), 4), ("NEW", 0x46C0, 4)],
                             "TryRunFromBattle's `if (!(gBattleTypeFlags & BATTLE_TYPE_DOUBLE))` bne (_NEW = nop: the roll runs)")
    return out


def find_link_wild_sites(rom, ranges, addr_of):
    """Task 3 (a joined wild battle is a link battle that runs like a wild one): code sites patched for the wild kind only.
    LINK_WILD_RUN_IMPOSSIBLE_BEQ: IsRunningFromBattleImpossible's `if (gBattleTypeFlags & BATTLE_TYPE_LINK) return
    BATTLE_RUN_SUCCESS` (compiled `movs r1, #2; ands r0, r1; cmp r0, #0; beq +0; b <success>`): the beq becomes `b +0`, so
    Shadow Tag / Arena Trap / Magnet Pull / wrapped can say "can't escape" at the prompt. LINK_WILD_RUN_ACTION_BEQ:
    HandleAction_Run's `if (gBattleTypeFlags & (LINK | RECORDED_LINK))` (the forfeit) beq becomes an unconditional b to the
    same target (the single battle's TryRunFromBattle).
    (TrySetLinkBattleTowerEnemyPartyLevel is task 2's LINK_HOOK for every kind: the enemies keep their level.)"""
    out = {}
    code = Thumb(rom, *ranges["IsRunningFromBattleImpossible"])
    found = [a + 6 for a in range(code.start + 4, code.end - 10, 2)
             if code.hw(a) == 0x2102 and code.hw(a + 2) == 0x4008 and code.hw(a + 4) == 0x2800 and code.hw(a + 6) == 0xD000
             and code.hw(a + 8) & 0xF800 == 0xE000]
    if len(found) != 1:
        die("IsRunningFromBattleImpossible: expected one BATTLE_TYPE_LINK test, found %d" % len(found))
    out["LINK_WILD_RUN_IMPOSSIBLE_BEQ"] = (found[0], [("INSN", 0xD000, 4), ("NEW", 0xE000, 4)],
        "IsRunningFromBattleImpossible's `if (LINK) return SUCCESS` beq (_NEW = b +0: the checks below run)")
    code = Thumb(rom, *ranges["HandleAction_Run"])
    found = [a + 6 for a, rd in code.literal_loads(0x02000002)
             if rd == 1 and code.hw(a + 2) == 0x4008 and code.hw(a + 4) == 0x2800 and code.hw(a + 6) & 0xFF00 == 0xD000]
    if len(found) != 1:
        die("HandleAction_Run: expected one LINK | RECORDED_LINK test, found %d" % len(found))
    h = code.hw(found[0])
    off = h & 0xFF   # a forward beq: the same target as a `b` with the same halfword offset
    out["LINK_WILD_RUN_ACTION_BEQ"] = (found[0], [("INSN", h, 4), ("NEW", 0xE000 | off, 4)],
        "HandleAction_Run's `if (LINK | RECORDED_LINK)` beq (_NEW = b to the same target: the escape roll, not the forfeit)")
    # CB2_HandleStartMultiPartnerBattle's `CreateTask(InitLinkBattleVsScreen, 0)` loads the task function from its literal pool;
    # for the wild kind that word is our no-op task (scripts/coop_link_emerald.s, link_entry_WILD_NOVS: DestroyTask), so no
    # "COOP VS NED / two trainers' names" screen comes before a wild battle.
    code = Thumb(rom, *ranges["CB2_HandleStartMultiPartnerBattle"])
    vs = ranges["InitLinkBattleVsScreen"][0] | 1
    found = sorted({lit for lit in range(code.start, code.end + 0x400, 4) if code.word(lit) == vs
                    and any(((a + 4) & ~3) + (code.hw(a) & 0xFF) * 4 == lit for a in range(code.start, code.end, 2)
                            if code.hw(a) & 0xF800 == 0x4800)})
    if len(found) != 1:
        die("CB2_HandleStartMultiPartnerBattle: expected one InitLinkBattleVsScreen literal, found %d" % len(found))
    out["LINK_WILD_VS_TASK_PTR"] = (found[0], [("PTR", vs, 8)],
        "CB2_HandleStartMultiPartnerBattle's CreateTask(InitLinkBattleVsScreen) literal (ours at COOP_EM_LINK_ENTRY_WILD_NOVS | 1)")
    # HandleEndTurn_RanFromBattle / HandleEndTurn_MonFled each end in `gBattleMainFunc = HandleEndTurn_FinishBattle` (a literal
    # pool word): for the wild kind it is our stub (link_entry_RAN_FINISH), which sends the battle's end to the other player
    # (the script's `end2` would end only the master's game) and then runs the game's own function.
    fin = ranges["HandleEndTurn_FinishBattle"][0] | 1
    for fn_name, site in (("HandleEndTurn_RanFromBattle", "LINK_WILD_RAN_FINISH_PTR"), ("HandleEndTurn_MonFled", "LINK_WILD_FLED_FINISH_PTR")):
        code = Thumb(rom, *ranges[fn_name])
        lits = sorted({((a + 4) & ~3) + (code.hw(a) & 0xFF) * 4 for a in range(code.start, code.end, 2)
                       if code.hw(a) & 0xF800 == 0x4800 and code.word(((a + 4) & ~3) + (code.hw(a) & 0xFF) * 4) == fin})
        if len(lits) != 1:
            die("%s: expected one HandleEndTurn_FinishBattle literal, found %d" % (fn_name, len(lits)))
        out[site] = (lits[0], [("PTR", fin, 8)],
                     "%s's `gBattleMainFunc = HandleEndTurn_FinishBattle` literal (ours at COOP_EM_LINK_ENTRY_RAN_FINISH | 1)" % fn_name)
    # sEndTurnFuncsTable's entries for B_OUTCOME_PLAYER_TELEPORTED (5) and B_OUTCOME_MON_TELEPORTED (10: Roar / Whirlwind / Teleport
    # ends) point at HandleEndTurn_FinishBattle directly: ours too, so the partner's game hears of the end.
    tbl = addr_of["sEndTurnFuncsTable"]
    for idx, site in ((5, "LINK_WILD_END_TELEPORTED_PTR"), (10, "LINK_WILD_END_MON_TELEPORTED_PTR")):
        if struct.unpack_from("<I", rom, tbl + 4 * idx - ROM_BASE)[0] != fin:
            die("sEndTurnFuncsTable[%d] isn't HandleEndTurn_FinishBattle" % idx)
        out[site] = (tbl + 4 * idx, [("PTR", fin, 8)], "sEndTurnFuncsTable[%d] = HandleEndTurn_FinishBattle (ours at COOP_EM_LINK_ENTRY_RAN_FINISH | 1)" % idx)
    # HandleIntroSlide's link task (the one task function a LINK battle gets, no symbol: found by its code) sets the VS letters'
    # callbacks through the sprite ids InitLinkBattleVsScreen left at gBattleStruct + 0x7D / 0x7E. The wild kind skips that screen,
    # the ids read 0 and sprite 0 - the healthbox of battler 0 - slid off the screen. The load of the callbacks' literal
    # (`ldr r4, [pc, #n]` after the state's `adds r0, #1; strh r0, [r1, #8]`, then `ldr r5, [pc]; ldr r0, [r5]; adds r0, #0x7D`) becomes a
    # `b` to the `movs r0, #0x48` (SetGpuReg WININ) that follows the sprite code, so the window wipe still runs.
    found = [a + 4 for a in range(0, len(rom) - 12, 2)
             if struct.unpack_from("<6H", rom, a) [0:2] == (0x3001, 0x8108) and struct.unpack_from("<6H", rom, a)[2] & 0xFF00 == 0x4C00
             and struct.unpack_from("<6H", rom, a)[3] & 0xFF00 == 0x4D00 and struct.unpack_from("<6H", rom, a)[4:6] == (0x6828, 0x307D)]
    if len(found) != 1:
        die("the intro slide's VS letter sprites: expected one site, found %d" % len(found))
    site = found[0]
    skip = next((b for b in range(site + 2, site + 0x100, 2) if struct.unpack_from("<HH", rom, b) == (0x2048, 0x213F)), None)
    if skip is None:
        die("the intro slide's VS letter sprites: no SetGpuReg(WININ) after 0x%08X" % (ROM_BASE + site))
    out["LINK_WILD_INTRO_VS_SPRITES"] = (ROM_BASE + site, [("INSN", struct.unpack_from("<H", rom, site)[0], 4), ("NEW", 0xE000 | ((skip - (site + 4)) // 2), 4)],
        "the intro slide's link task state that sets the VS letters' callbacks, ids at gBattleStruct + 0x7D / 0x7E (_NEW = b past them: a wild battle has no VS screen, the ids read 0 = the player's healthbox sprite)")
    return out
def find_link_end_sites(rom, ranges, addr_of):
    """Task 5: CB2_InitEndLinkBattle's `if (gBattleTypeFlags & BATTLE_TYPE_FRONTIER)` (mask 0x003F0100) compiled as
    `ldr r0, =mask; ands r0, r1; cmp r0, #0; beq <the VS screen + record prompt>`: the beq becomes a nop (0x46C0) while a joined link
    battle runs, so every link end takes the frontier branch - SetMainCallback2(gMain.savedCallback) + the three Free* - the
    game's normal end, with no VS screen, record prompt or link reconnect."""
    code = Thumb(rom, *ranges["CB2_InitEndLinkBattle"])
    found = []
    for a, rd in code.literal_loads(0x003F0100):
        if rd == 0 and code.hw(a + 2) == 0x4008 and code.hw(a + 4) == 0x2800 and code.hw(a + 6) & 0xFF00 == 0xD000:
            found.append(a + 6)
    if len(found) != 1:
        die("CB2_InitEndLinkBattle: expected one BATTLE_TYPE_FRONTIER test, found %d" % len(found))
    return {"LINK_END_BEQ": (found[0], code.hw(found[0]),
                             "CB2_InitEndLinkBattle's `if (gBattleTypeFlags & BATTLE_TYPE_FRONTIER)` beq (task 5: a nop, 0x46C0, sends every link end down the normal-end branch)")}


def find_link_rewards_sites(rom, ranges, k, addr_of):
    """Task 4 (EXP, the BAG and catching in a link battle): the literals and the instruction pk_coop_link_rewards.c patches
    while a link battle runs, each as name -> (address, [(suffix, value, digits)], comment): the file's bytes (_ORIG, to
    check) and ours (_NEW), found by their shape and checked here."""
    out = {}
    link, frontier, np = k["BATTLE_TYPE_LINK"], k["BATTLE_TYPE_FRONTIER"], k["BATTLE_TYPE_FRONTIER_NO_PYRAMID"]

    def literal_site(fn, value, new, what):
        code = Thumb(rom, *ranges[fn])
        lits = {((a + 4) & ~3) + (code.hw(a) & 0xFF) * 4 for a, _ in code.literal_loads(value)}
        if len(lits) != 1:
            die("%s: expected one literal 0x%08X, found %d" % (fn, value, len(lits)))
        lit = lits.pop()
        return lit, [("ORIG", value, 8), ("NEW", new, 8)], what

    # Cmd_getexp case 0: `if (side != OPPONENT || flags & (LINK | RECORDED_LINK | TRAINER_HILL | FRONTIER | SAFARI | EREADER))`
    # skips the EXP. The link and every Frontier bit come out for a joined battle (the tower flag is the link multi's).
    old = k["BATTLE_TYPE_LINK"] | k["BATTLE_TYPE_RECORDED_LINK"] | k["BATTLE_TYPE_TRAINER_HILL"] | frontier \
        | k["BATTLE_TYPE_SAFARI"] | k["BATTLE_TYPE_EREADER_TRAINER"]
    out["RW_GETEXP_SKIP_MASK"] = literal_site(
        "Cmd_getexp", old, old & ~(link | frontier),
        "Cmd_getexp case 0's no-EXP battle types (_NEW: the link and Frontier bits out)")

    # HandleTurnActionSelectionState, B_ACTION_USE_ITEM: `if (flags & (LINK | FRONTIER_NO_PYRAMID | EREADER_TRAINER | RECORDED_LINK))`
    # answers "Items can't be used now".
    old = link | np | k["BATTLE_TYPE_EREADER_TRAINER"] | k["BATTLE_TYPE_RECORDED_LINK"]
    out["RW_BAG_MASK"] = literal_site(
        "HandleTurnActionSelectionState", old, old & ~(link | np),
        "HandleTurnActionSelectionState's no-items battle types (_NEW: the link and Frontier bits out)")

    # Cmd_getexp case 2's traded-Pokemon branch: `if (INGAME_PARTNER && expGetterMonId >= 3) no boost else boost`, compiled as
    #   movs r1, #0x80; lsls r1, r1, #15; ands r0, r1; cmp r0, #0; beq BOOST; ldr r0, [r4]; ldrb r0, [r0, #16]; cmp r0, #2; bls BOOST
    # The first beq becomes a nop: a Pokemon of slots 3-5 (the partner's, whose own game is not the master's) gets no boost.
    code = Thumb(rom, *ranges["Cmd_getexp"])
    found = [a + 8 for a in range(code.start, code.end - 20, 2)
             if code.hw(a) == 0x2180 and code.hw(a + 2) == 0x03C9 and code.hw(a + 4) == 0x4008 and code.hw(a + 6) == 0x2800
             and code.hw(a + 8) & 0xFF00 == 0xD000 and code.hw(a + 12) == 0x7C00 and code.hw(a + 14) == 0x2802]
    if k["BATTLE_TYPE_INGAME_PARTNER"] != 0x80 << 15 or len(found) != 1:
        die("Cmd_getexp: expected one INGAME_PARTNER test before the traded boost, found %d" % len(found))
    out["RW_TRADED_BEQ"] = (found[0], [("INSN", code.hw(found[0]), 4), ("NEW", 0x46C0, 4)],
                            "Cmd_getexp's `if (INGAME_PARTNER ...)` beq to the boost (_NEW = nop: slots 3-5 are never boosted)")
    return out


def assemble(equ, asm, code_addr, code_max, prefix):
    """An assembly file at `code_addr` with `.set COOP_EM_<name>, value` for every value in front: (code bytes,
    [(ENTRY name, address)], [(ASM name, value)]) - its <prefix>_entry_<NAME> labels and its absolute <prefix>_* symbols.
    The link bridge's file (scripts/coop_link_emerald.s) is the prefix "link" (link_entry_<NAME>, link_*) at LINK_CODE_ADDR."""
    with open(asm) as f:
        src = f.read()
    with tempfile.TemporaryDirectory() as tmp:
        s, o, elf, bin_ = (os.path.join(tmp, n) for n in ("coop.s", "coop.o", "coop.elf", "coop.bin"))
        with open(s, "w") as f:
            f.write("".join("    .set COOP_EM_%s, 0x%X\n" % (n, v) for n, v in equ.items()) + src)
        subprocess.run(["arm-none-eabi-as", "-mcpu=arm7tdmi", "-mthumb", "-o", o, s], check=True)
        subprocess.run(["arm-none-eabi-ld", "-nostdlib", "-Ttext=0x%08X" % code_addr, "-e", "0x%08X" % code_addr,
                        "-o", elf, o], check=True)
        subprocess.run(["arm-none-eabi-objcopy", "-O", "binary", "-j", ".text", elf, bin_], check=True)
        with open(bin_, "rb") as f:
            code = f.read()
        syms = read_symbols(elf)
    if not 0 < len(code) <= code_max:
        die("our code is %d bytes: it must fit in 0x%X" % (len(code), code_max))
    entries = []
    for name, found in sorted(syms.items()):
        m = re.fullmatch(prefix + r"_entry_([A-Z0-9_]+)", name)
        if m:
            addr = found[0][0] & ~1
            if not code_addr <= addr < code_addr + len(code) or found[0][1] != "T":
                die("%s at 0x%08X isn't a global in our code" % (name, addr))
            entries.append((m.group(1), addr))
    if not entries:
        die("no %s_entry_ labels in %s" % (prefix, asm))
    asm_syms = [(name[len(prefix + "_"):].upper(), found[0][0]) for name, found in sorted(syms.items())
                if name.startswith(prefix + "_") and not name.startswith(prefix + "_entry_") and found[0][1] == "A"]
    return code, entries, asm_syms


def main():
    if len(sys.argv) != 2:
        die("usage: gen_coop_emerald.py <pokeemerald dir>")
    decomp = os.path.abspath(sys.argv[1])
    with open(os.path.join(decomp, "pokeemerald.gba"), "rb") as f:
        rom = f.read()
    sha1 = hashlib.sha1(rom).hexdigest()
    if sha1 != RETAIL_SHA1:
        die("pokeemerald.gba is %s, not retail's %s: refusing to write the header" % (sha1, RETAIL_SHA1))
    syms = read_symbols(os.path.join(decomp, "pokeemerald.elf"))

    addr_of = {}
    for _, elf in FUNCTIONS:
        addr_of[elf] = symbol(syms, elf, "Tt") & ~1
    for name in ROM_DATA:
        addr_of[name] = symbol(syms, name, "Rr")
    for name in RAM:
        addr_of[name] = symbol(syms, name, "BbDd")
    for name in BATTLE_SCRIPTS:
        addr_of[name] = symbol(syms, name, "Dd")
        if not ROM_BASE <= addr_of[name] < ROM_BASE + len(rom):
            die("%s at 0x%08X isn't in the ROM" % (name, addr_of[name]))
    for name in ROM_DATA:
        if not ROM_BASE <= addr_of[name] < ROM_BASE + len(rom):
            die("%s at 0x%08X isn't in the ROM" % (name, addr_of[name]))
    for name in RAM:
        if not (0x02000000 <= addr_of[name] < 0x02040000 or 0x03000000 <= addr_of[name] < 0x03008000):
            die("%s at 0x%08X isn't in EWRAM / IWRAM" % (name, addr_of[name]))

    # A function's range: up to the next code symbol (its literal pool included).
    code_starts = sorted({a & ~1 for v in syms.values() for a, t in v if t in "Tt" and ROM_BASE <= a < ROM_BASE + len(rom)})
    ranges = {}
    for _, elf in FUNCTIONS:
        start = addr_of[elf]
        after = [a for a in code_starts if a > start]
        ranges[elf] = (start, after[0] if after else ROM_BASE + len(rom))

    k = dict(zip([n for n, _ in OFFSETS] + CONSTANTS, probe_values(decomp, [e for _, e in OFFSETS] + CONSTANTS)))
    sites = find_link_end_sites(rom, ranges, addr_of)   # task 5
    arm_sites = find_arm_sites(rom, ranges, k, addr_of)
    arm_sites.update(find_link_wild_sites(rom, ranges, addr_of))   # task 3
    rw_sites = find_link_rewards_sites(rom, ranges, k, addr_of)

    def crc(addr):
        off = addr - ROM_BASE
        return zlib.crc32(rom[off:off + CRC_BYTES]) & 0xFFFFFFFF

    L = ["// Generated by scripts/gen_coop_emerald.py from pret/pokeemerald built byte for byte as retail English",
         "// Emerald (BPEE, SHA-1 %s) - don't edit by hand." % RETAIL_SHA1,
         "// Functions are even addresses (no Thumb bit); COOP_EM_CRC_<f> = CRC32 of the %d ROM bytes at <f>." % CRC_BYTES,
         "#pragma once", "", "#include <stdint.h>", "", "// Functions (ROM code)."]
    width = 52
    equ = {}   # every value, for the assembler

    def define(name, value, comment=None):
        equ[name] = value
        line = "#define %-*s 0x%08Xu" % (width, "COOP_EM_" + name, value)
        L.append(line + ("   // " + comment if comment else ""))

    for name, elf in FUNCTIONS:
        define(name, addr_of[elf], None if name == elf else elf)
    L += ["", "// Their code CRCs."]
    for name, elf in FUNCTIONS:
        define("CRC_" + name, crc(addr_of[elf]))
    L += ["", "// ROM tables."]
    for name in ROM_DATA:
        define(name, addr_of[name])
    L += ["", "// Battle scripts (ROM data)."]
    for name in EMIT_SCRIPTS:
        define(name, addr_of[name])
    L += ["", "// RAM."]
    for name in RAM:
        define(name, addr_of[name])
    L += ["", "// Struct offsets and sizes (agbcc, the decomp's headers)."]
    for name, expr in OFFSETS:
        equ[name] = k[name]
        L.append("#define %-*s %d   // %s" % (width, "COOP_EM_" + name, k[name], expr))
    L += ["", "// Constants."]
    for name in CONSTANTS:
        equ[name] = k[name]
        L.append("#define %-*s %d" % (width, "COOP_EM_" + name, k[name]))
    L += ["", "// Task 6 (story battles): the pick script's opcodes and `special ChooseHalfPartyForBattle`'s index."]
    for name, value in story_constants(decomp, syms, rom).items():
        equ[name] = value
        L.append("#define %-*s %d" % (width, "COOP_EM_" + name, value))
    L += ["", "// Code sites: the instruction's address, then its original halfword (_INSN) for an exact check."]
    for name, (addr, insn, what) in sites.items():
        define(name, addr, what)
        equ[name + "_INSN"] = insn
        L.append("#define %-*s 0x%04Xu" % (width, "COOP_EM_" + name + "_INSN", insn))
    L += ["", "// The joined wild battle's code sites (pk_coop_link_wild.c): the address, the file's bytes and ours, little-endian."]
    for name, (addr, values, what) in arm_sites.items():
        define(name, addr, what)
        for suffix, value, digits in values:
            equ[name + "_" + suffix] = value
            L.append("#define %-*s 0x%0*Xu" % (width, "COOP_EM_" + name + "_" + suffix, digits, value))

    L += ["", "// Plan 6 task 4 (EXP, the BAG and catching in a link battle): the literals / the instruction pk_coop_link_rewards.c patches",
          "// while a link battle runs (layout as above: _ORIG = the file's, _NEW = ours)."]
    for name, (addr, values, what) in rw_sites.items():
        define(name, addr, what)
        for suffix, value, digits in values:
            equ[name + "_" + suffix] = value
            L.append("#define %-*s 0x%0*Xu" % (width, "COOP_EM_" + name + "_" + suffix, digits, value))

    # the items whose battle use is the PP recovery (Ether / Elixir family): their gItems battleUseFunc pointers go to our wrapper
    pp_items = [i for i in range(k["ITEMS_COUNT"])
                if struct.unpack_from("<I", rom, addr_of["gItems"] - ROM_BASE + i * k["OFF_ITEM_SIZE"] + k["OFF_ITEM_BATTLE_USE_FUNC"])[0]
                == addr_of["ItemUseInBattle_PPRecovery"] | 1]
    if not pp_items:
        die("no item uses ItemUseInBattle_PPRecovery")
    L.append("#define %-*s %d   // items whose gItems battleUseFunc is ItemUseInBattle_PPRecovery: COOP_EM_PP_ITEM_<n> = their ids" % (width, "COOP_EM_PP_ITEM_COUNT", len(pp_items)))
    for n, item in enumerate(pp_items):
        L.append("#define %-*s %d" % (width, "COOP_EM_PP_ITEM_%d" % n, item))

    # Plan 6 (joined battles as link battles): the link bridge's own block, its head patches and the player handler's loop.
    L += ["// The link bridge (plan 6, scripts/coop_link_emerald.s, pk_coop_link.c): its own 4 KB at COOP_EM_LINK_CODE_ADDR over the",
          "// file's 0xFF bytes. COOP_EM_LINK_ENTRY_<NAME> = its routines (even: add the Thumb bit). COOP_EM_LINK_HEAD_<f>_ORIG0 /",
          "// _ORIG1 = the 8 bytes at the start of game function <f> (two little-endian words) as the file has them, _NEW0 =",
          "// `ldr r3, [pc, #0]; bx r3`, _NEW1 = the literal: the routine's address | 1 - the head patch that replaces <f> while a",
          "// link battle runs. COOP_EM_HandleInputChooseAction = the player's battle controller waiting for the action pick."]
    for name in LOWEST_SYMBOLS:
        found = [a for a, t in syms.get(name, []) if t in "Tt" and ROM_BASE <= a < ROM_BASE + len(rom)]
        if not found:
            die("%s: no code symbol" % name)
        define(name, min(found) & ~1)
    link_code, link_entries, link_syms = assemble(equ, ASM_LINK, LINK_CODE_ADDR, LINK_CODE_MAX, "link")
    off = LINK_CODE_ADDR - ROM_BASE
    if rom[off:off + len(link_code)] != b"\xff" * len(link_code):
        die("the ROM isn't empty (0xFF) at 0x%08X: no room for the link bridge's code there" % LINK_CODE_ADDR)
    define("LINK_CODE_ADDR", LINK_CODE_ADDR)
    entry_of = dict(link_entries)
    for name, addr in link_entries:
        define("LINK_ENTRY_" + name, addr)
    # task 2: BattleAI_SetupAIData gives every foe of a Battle Tower / Frontier battle the same three AI scripts; a joined battle
    # (the tower flag is on for the link multi) must use each trainer's own. Two patches, in only while a link battle runs: the
    # pool word of the `FRONTIER | EREADER_TRAINER | TRAINER_HILL | SECRET_BASE` test loses the tower bit, and the TWO_OPPONENTS test
    # (movs r0, #0x80; lsls r0, r0, #8) becomes 0x8100, true for the tower bit: A's aiFlags | B's (B = A for a single trainer).
    ai_start, ai_end = ranges["BattleAI_SetupAIData"]
    ai_mask = k["BATTLE_TYPE_FRONTIER"] | k["BATTLE_TYPE_EREADER_TRAINER"] | k["BATTLE_TYPE_TRAINER_HILL"] | k["BATTLE_TYPE_SECRET_BASE"]
    ai_words = [a for a in range(ai_start, ai_end - 3, 4) if struct.unpack_from("<I", rom, a - ROM_BASE)[0] == ai_mask]
    ai_two = [a for a in range(ai_start, ai_end - 7, 2) if rom[a - ROM_BASE:a - ROM_BASE + 8] == bytes.fromhex("8020000201400029")]
    if len(ai_words) != 1 or len(ai_two) != 1 or not ai_mask & k["BATTLE_TYPE_BATTLE_TOWER"]:
        die("BattleAI_SetupAIData: expected one frontier-mask pool word and one TWO_OPPONENTS test, found %d / %d" % (len(ai_words), len(ai_two)))
    define("AI_MASK_ADDR", ai_words[0])
    define("AI_MASK_WORD", ai_mask)
    define("AI_MASK_NEW", ai_mask & ~k["BATTLE_TYPE_BATTLE_TOWER"])
    define("AI_TWO_ADDR", ai_two[0])
    define("AI_TWO_INSN", 0x2080)
    define("AI_TWO_NEW", 0x2081)
    for hook, entry in LINK_HOOKS + STORY_HOOKS:
        fn = addr_of[hook]
        if fn % 4:
            die("%s at 0x%08X isn't word aligned: the head patch's literal would not line up" % (hook, fn))
        if entry not in entry_of:
            die("no link_entry_%s in %s" % (entry, ASM_LINK))
        w0, w1 = struct.unpack_from("<II", rom, fn - ROM_BASE)
        for suffix, value in (("ORIG0", w0), ("ORIG1", w1), ("NEW0", 0x47184B00), ("NEW1", entry_of[entry] | 1)):
            equ["LINK_HEAD_%s_%s" % (hook, suffix)] = value
            L.append("#define %-*s 0x%08Xu" % (width, "COOP_EM_LINK_HEAD_%s_%s" % (hook, suffix), value))
    L += ["", "// The bridge's RAM layout and values its code was written for (pk_coop_link.c checks them against pk_coop.h)."]
    for name, value in link_syms:
        L.append(("#define %-*s 0x%08Xu" if value > 0xFFFF else "#define %-*s %d") % (width, "COOP_EM_ASM_LINK_" + name, value))
    L += ["", "static const uint8_t COOP_EM_LINK_CODE[%d] = {" % len(link_code)]
    for i in range(0, len(link_code), 16):
        L.append("    " + " ".join("0x%02X," % b for b in link_code[i:i + 16]))
    L += ["};", ""]
    with open(OUT_H, "w", newline="\n") as f:
        f.write("\n".join(L))
    print("wrote %s (%d functions, %d ROM tables, %d RAM, %d offsets, %d constants, %d sites, %d bridge code bytes, %d bridge entries)"
          % (os.path.normpath(OUT_H), len(FUNCTIONS), len(ROM_DATA), len(RAM), len(OFFSETS), len(CONSTANTS), len(sites),
             len(link_code), len(link_entries)))
    write_kotlin(rom, os.path.join(decomp, "pokeemerald.elf"))
    append_palette_fade(decomp, syms)


# --- Co-op presence (the partner on screen): the ROM addresses Kotlin needs, in their own output. ---

OUT_KT = os.path.join(HERE, "..", "app", "src", "main", "kotlin", "com", "pokedaisy", "app", "companion", "data", "coop",
                      "CoopEmeraldGen.kt")
# gFieldEffectObjectPic_SurfBlob: three 32x32 4bpp frames (south, north, west), drawn with the hero's palette.
SURF_BLOB_BYTES = 3 * 512


def write_kotlin(rom, elf):
    """CoopEmeraldGen.kt: the surf blob's ROM offset and the CRC32 of its bytes (PartnerArt refuses a ROM without them)."""
    out = subprocess.run(["arm-none-eabi-nm", "-S", elf], check=True, capture_output=True, text=True).stdout
    found = [p for p in (l.split() for l in out.splitlines()) if len(p) == 4 and p[3] == "gFieldEffectObjectPic_SurfBlob"
             and p[2] in "Rr"]
    if len(found) != 1:
        die("gFieldEffectObjectPic_SurfBlob: expected one ROM symbol, nm -S has %s" % found)
    addr, size = int(found[0][0], 16), int(found[0][1], 16)
    if size != SURF_BLOB_BYTES:
        die("gFieldEffectObjectPic_SurfBlob is %d bytes, not %d" % (size, SURF_BLOB_BYTES))
    off = addr - ROM_BASE
    if not 0 <= off <= len(rom) - size:
        die("gFieldEffectObjectPic_SurfBlob at 0x%08X isn't in the ROM" % addr)
    crc = zlib.crc32(rom[off:off + size]) & 0xFFFFFFFF
    L = ["// generated by scripts/gen_coop_emerald.py, do not edit",
         "// (pret/pokeemerald built byte for byte as retail English Emerald, SHA-1 %s)" % RETAIL_SHA1,
         "package com.pokedaisy.app.companion.data.coop",
         "",
         "object CoopEmeraldGen {",
         "    /** gFieldEffectObjectPic_SurfBlob's ROM offset: 3 frames x 512 bytes (south, north, west). */",
         "    const val SURF_BLOB_PIC: Int = 0x%06X" % off,
         "",
         "    /** CRC32 of those %d bytes in the retail ROM. */" % size,
         "    const val SURF_BLOB_CRC: Long = 0x%08XL" % crc,
         "}",
         ""]
    with open(OUT_KT, "w", newline="\n") as f:
        f.write("\n".join(L))
    print("wrote %s (surf blob 0x%08X, CRC32 %08X)" % (os.path.normpath(OUT_KT), addr, crc))


# --- Co-op presence: the screen fade (gPaletteFade), appended to the header in its own block. ---

# struct PaletteFadeControl's bitfields have no offsetof: each is set to all ones in a const struct agbcc compiles, and its
# mask / shift / offset read from the bytes it emitted. (define stem, field)
PALFADE_FIELDS = [("Y", "y"), ("BLEND", "blendColor"), ("ACTIVE", "active"), ("MODE", "mode")]


def compile_probe(decomp, src):
    """agbcc's assembly for `src` (the Makefile's preprocessor flags)."""
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "coop_fade_probe.c")
        with open(path, "w") as f:
            f.write(src)
        pre = subprocess.run(["cc", "-E", "-iquote", "include", "-Wno-trigraphs", "-DMODERN=0",
                              "-I", "tools/agbcc/include", "-I", "tools/agbcc", "-nostdinc", "-undef", "-std=gnu89",
                              path], cwd=decomp, check=True, capture_output=True, text=True).stdout
        return subprocess.run([os.path.join(decomp, "tools", "agbcc", "bin", "agbcc"), "-mthumb-interwork", "-O2",
                               "-fhex-asm", "-o", "-", "-"], cwd=decomp, input=pre, check=True, capture_output=True,
                              text=True).stdout


def probe_bytes(asm, label):
    """The bytes of the object `label:` in agbcc's assembly (.space / .byte / .2byte / .word)."""
    out, inside = bytearray(), False
    for line in asm.splitlines():
        line = line.strip()
        if line == label + ":":
            inside = True
            continue
        if not inside:
            continue
        m = re.match(r"\.(space|byte|2byte|short|word|4byte|long)\s+(\S+)", line)
        if not m:
            break
        kind, v = m.group(1), int(m.group(2), 0)
        if kind == "space":
            out += bytes(v)
        else:
            out += v.to_bytes({"byte": 1, "2byte": 2, "short": 2}.get(kind, 4), "little")
    return bytes(out)


def append_palette_fade(decomp, syms):
    """COOP_EM_gPaletteFade + the offsets / masks / shifts of the fields the partner's colours follow, appended to the header."""
    addr = symbol(syms, "gPaletteFade", "BbDd")
    if not (0x02000000 <= addr < 0x02040000 or 0x03000000 <= addr < 0x03008000):
        die("gPaletteFade at 0x%08X isn't in EWRAM / IWRAM" % addr)
    src = '#include "global.h"\n#include "palette.h"\n'
    src += "const int coop_pf_size = sizeof(struct PaletteFadeControl);\n"
    src += "const int coop_pf_selected = offsetof(struct PaletteFadeControl, multipurpose1);\n"
    for _, field in PALFADE_FIELDS:
        src += "const struct PaletteFadeControl coop_pf_%s = { .%s = -1 };\n" % (field, field)
    asm = compile_probe(decomp, src)
    size = int.from_bytes(probe_bytes(asm, "coop_pf_size"), "little")
    selected = int.from_bytes(probe_bytes(asm, "coop_pf_selected"), "little")
    L = ["", "// The screen fade (palette.c's gPaletteFade): the partner's colours follow it. A bitfield's value is",
         "// (halfword at _OFF >> _SHIFT) & (_MASK >> _SHIFT); the masks come from agbcc's own layout of struct PaletteFadeControl",
         "// (%d bytes), probed with every field set to all ones." % size,
         "#define %-*s 0x%08Xu" % (52, "COOP_EM_gPaletteFade", addr),
         "#define %-*s %d   // offsetof(struct PaletteFadeControl, multipurpose1): selectedPalettes while a normal fade runs"
         % (52, "COOP_EM_PALFADE_SELECTED_OFF", selected)]
    for stem, field in PALFADE_FIELDS:
        raw = probe_bytes(asm, "coop_pf_" + field)
        if len(raw) != size:
            die("coop_pf_%s is %d bytes, struct is %d" % (field, len(raw), size))
        bits = int.from_bytes(raw, "little")
        if bits == 0:
            die("%s: no bit set" % field)
        shift = (bits & -bits).bit_length() - 1
        off = shift // 16 * 2
        mask = bits >> (off * 8)
        if mask >= 1 << 16:
            die("%s: bits 0x%X don't fit one halfword" % (field, bits))
        shift -= off * 8
        L.append("#define %-*s %d   // %s" % (52, "COOP_EM_PALFADE_%s_OFF" % stem, off, field))
        L.append("#define %-*s %d" % (52, "COOP_EM_PALFADE_%s_SHIFT" % stem, shift))
        L.append("#define %-*s 0x%04Xu" % (52, "COOP_EM_PALFADE_%s_MASK" % stem, mask))
    L.append("")
    with open(OUT_H, "a", newline="\n") as f:
        f.write("\n".join(L))
    print("appended gPaletteFade 0x%08X to %s" % (addr, os.path.normpath(OUT_H)))


if __name__ == "__main__":
    main()
