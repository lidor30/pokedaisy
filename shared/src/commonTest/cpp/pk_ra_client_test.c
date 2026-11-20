#include "pk_api.h"
#include "pk_ra_client.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define REQUIRE(test) do { if (!(test)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #test); exit(1); } } while (0)

static const char set[] =
    "{\"Success\":true,\"GameId\":1234,\"Title\":\"PokeDaisy diagnostic\",\"ConsoleId\":5,"
    "\"ImageIconUrl\":\"https://example.invalid/game.png\","
    "\"RichPresenceGameId\":1234,\"RichPresencePatch\":\"\",\"Sets\":[{"
    "\"AchievementSetId\":1111,\"GameId\":1234,\"Title\":null,\"Type\":\"core\","
    "\"ImageIconUrl\":\"https://example.invalid/game.png\","
    "\"Achievements\":[{\"ID\":55,\"Title\":\"Memory changed\",\"Description\":\"Diagnostic trigger\","
    "\"Flags\":3,\"Points\":5,\"MemAddr\":\"0xH0001=7\",\"Author\":\"Fixture\","
    "\"BadgeName\":\"00001\",\"Created\":1367266583,\"Modified\":1376929305}],\"Leaderboards\":[{\"ID\":44,\"Title\":\"Diagnostic scores\",\"Description\":\"Sample board\",\"Mem\":\"STA:0xH000C=1::CAN:0xH000D=1::SUB:0xH000D=2::VAL:0x 000E\",\"Format\":\"SCORE\"}]}]}";

static const char leaderboard[] =
    "{\"Success\":true,\"LeaderboardData\":{\"LBID\":44,\"GameID\":1234,\"LowerIsBetter\":1,\"LBTitle\":\"Diagno"
    "stic scores\",\"LBDesc\":\"Sample board\",\"LBFormat\":\"SCORE\",\"LBMem\":\"STA:0xH000C=1::CAN:0xH000D=1:"
    ":SUB:0xH000D=2::VAL:0x 000E\",\"LBAuthor\":null,\"LBCreated\":\"2013-10-20 22:12:21\",\"LBUpdated\":\"20"
    "21-06-14 08:18:19\",\"TotalEntries\":78,\"Entries\":[{\"User\":\"Fixture\",\"Score\":1234,\"Rank\":1,\"Index"
    "\":1,\"DateSubmitted\":1615654895}]}}";

static bool loggedIn, loaded, triggered, submitted, completed, scores, allProgress;

static void request(int id, const char* url, const char* post, const char* contentType) {
    REQUIRE(url && post && contentType);
    const char* response;
    if (strstr(post, "r=login2")) {
        REQUIRE(strstr(post, "&u=Fixture") && strstr(post, "&p=LocalOnly"));
        response = "{\"Success\":true,\"User\":\"Fixture\",\"Token\":\"FakeToken\",\"Score\":7,\"SoftcoreScore\":2}";
    } else if (strstr(post, "r=achievementsets")) {
        REQUIRE(strstr(post, "0123456789ABCDEF"));
        response = set;
    } else if (strstr(post, "r=startsession")) {
        REQUIRE(strstr(post, "h=0"));
        response = "{\"Success\":true,\"Unlocks\":[],\"HardcoreUnlocks\":[]}";
    } else if (strstr(post, "r=awardachievement")) {
        REQUIRE(strstr(post, "&a=55") && strstr(post, "&h=0"));
        response = "{\"Success\":true,\"Score\":7,\"SoftcoreScore\":7,\"AchievementID\":55,\"AchievementsRemaining\":0}";
        submitted = true;
    } else if (strstr(post, "r=lbinfo")) {
        REQUIRE(strstr(post, "&i=44"));
        response = leaderboard;
    } else if (strstr(post, "r=allprogress")) {
        REQUIRE(strstr(post, "&c=5"));
        response = "{\"Success\":true,\"Response\":{\"1234\":{\"Achievements\":1,\"Unlocked\":1}}}";
    } else {
        REQUIRE(strstr(post, "r=ping"));
        response = "{\"Success\":true}";
    }
    pk_ra_queue_response(id, 200, (const uint8_t*) response, strlen(response));
}

static void login(int result, const char* error, const struct pk_ra_user* user) {
    REQUIRE(result == 0 && !error && user);
    REQUIRE(!strcmp(user->username, "Fixture") && !strcmp(user->token, "FakeToken") && user->softcore_score == 2);
    loggedIn = true;
}

static void loadedGame(int result, const char* error, const struct pk_ra_game* game) {
    if (error) fprintf(stderr, "game load: %s\n", error);
    REQUIRE(result == 0 && game->id == 1234 && game->achievements == 1);
    REQUIRE(!strcmp(game->title, "PokeDaisy diagnostic") && !strcmp(game->hash, "0123456789ABCDEF"));
    loaded = true;
}

static void event(const struct pk_ra_event* event) {
    if (event->type == 15) completed = true;
    if (event->type != 1) return;
    REQUIRE(event->id == 55 && event->points == 5 && !strcmp(event->title, "Memory changed"));
    triggered = true;
}

static void fetchBoard(int token, bool around, int result, const char* error, const uint8_t* records,
                       size_t length, int total, int userIndex) {
    REQUIRE(token == 12 && !around && result == 0 && !error);
    REQUIRE(records && length && total == 78 && userIndex >= 0);
    REQUIRE(strstr((const char*) records, "1\x1F" "Fixture"));
    scores = true;
}

static void fetchProgress(int token, int result, const uint8_t* records, size_t length) {
    REQUIRE(token == 13 && result == 0 && records);
    const char expected[] = "1234\x1F" "1\x1F" "1\x1F" "0\x1E";
    REQUIRE(length == sizeof expected - 1 && memcmp(records, expected, length) == 0);
    allProgress = true;
}

int main(int argc, char** argv) {
    REQUIRE(argc == 2);
    char rom[1024], info[2048];
    snprintf(rom, sizeof rom, "%s/diagnostic.gba", argv[1]);
    REQUIRE(pk_api_init(rom, NULL));
    struct pk_ra_callbacks callbacks = {
        .request = request, .login = login, .game_loaded = loadedGame, .event = event,
        .leaderboard = fetchBoard, .all_progress = fetchProgress
    };
    REQUIRE(pk_ra_init(&callbacks) && pk_ra_ready());
    pk_ra_user_agent(info, sizeof info);
    REQUIRE(info[0] != '\0');
    pk_ra_login_password("Fixture", "LocalOnly");
    REQUIRE(!loggedIn);
    pk_ra_pump();
    REQUIRE(loggedIn);
    REQUIRE(pk_ra_load_game("0123456789ABCDEF"));
    REQUIRE(!loaded);
    pk_ra_pump();
    REQUIRE(loaded);
    size_t length;
    uint8_t* records = pk_ra_achievement_list(&length);
    REQUIRE(records && length && strstr((const char*) records, "55\x1F" "Memory changed"));
    free(records);
    records = pk_ra_leaderboard_list(&length);
    REQUIRE(records && length && strstr((const char*) records, "44\x1F" "Diagnostic scores"));
    free(records);
    pk_ra_fetch_leaderboard(12, 44, false, 1, 10);
    pk_ra_fetch_all_progress(13, 5);
    REQUIRE(!scores && !allProgress);
    pk_ra_pump();
    REQUIRE(scores && allProgress);
    uint8_t value = 0;
    REQUIRE(pk_api_write(0x03000001, &value, 1));
    pk_ra_frame();
    REQUIRE(!triggered);
    size_t size = 0;
    uint8_t* progress = pk_ra_serialize(&size);
    REQUIRE(progress && size > 0);
    pk_ra_deserialize(progress, size);
    free(progress);
    value = 7;
    REQUIRE(pk_api_write(0x03000001, &value, 1));
    pk_ra_idle();
    REQUIRE(!triggered);
    pk_ra_frame();
    REQUIRE(triggered && submitted);
    pk_ra_pump();
    pk_ra_debug_status(info, sizeof info);
    REQUIRE(strstr(info, "unlocked=1"));
    pk_ra_unload_game();
    REQUIRE(completed);
    REQUIRE(pk_ra_serialize(&size) == NULL && size == 0);
    pk_ra_logout();
    pk_api_deinit();
    return 0;
}
