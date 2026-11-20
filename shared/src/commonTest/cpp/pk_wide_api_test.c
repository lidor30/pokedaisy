// EXPAND's existing renderer through the portable API, using original diagnostic cartridges only.
#include "pk_api.h"
#include "pk_session.h"
#include <limits.h>
#include <stdio.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr,"FAIL %s:%d: %s\n",__FILE__,__LINE__,#c); return 1; } } while(0)

int main(int argc,char**argv) {
    CHECK(argc==2);
    CHECK(!pk_api_field_knows(0));
    CHECK(pk_api_field_knows(0x1F1C08FB));
    CHECK(!pk_api_wide_knows(0));
    CHECK(pk_api_wide_knows(0x1F1C08FB)); // Existing public retail Emerald table.
    CHECK(!pk_api_wide_configure(0x1F1C08FB)); // No core must never configure a renderer.
    CHECK(!pk_api_wide_add_derived(1,0x1F1C08FB,NULL,0));
    CHECK(pk_api_wide_video()==NULL);
    CHECK(pk_api_wide_width()==0 && pk_api_wide_height()==0);
    CHECK(pk_api_wide_wants_close());
    CHECK(pk_api_wide_scene()==PK_API_WIDE_SCENE_OFF);
    for(int platform=0;platform<2;platform++) {
        char path[1024];snprintf(path,sizeof path,"%s/diagnostic.%s",argv[1],platform?"gb":"gba");
        CHECK(pk_api_init(path,NULL));
        const void* buffer=pk_api_wide_video();CHECK(buffer!=NULL);
        CHECK(!pk_api_wide_configure(0));
        pk_api_wide_set_view(-20,-30,INT_MAX,INT_MAX);
        CHECK(pk_session.viewW==PK_API_WIDE_MAX_WIDTH && pk_session.viewH==PK_API_WIDE_MAX_HEIGHT);
        CHECK(pk_session.viewX==-20 && pk_session.viewY==-30);
        pk_api_wide_set_view(0,0,-1,-1);
        CHECK(pk_session.viewW==0 && pk_session.viewH==0);
        pk_api_wide_set_draw(false);
        for(int i=0;i<12;i++) {
            pk_api_run_frame();
            CHECK(pk_api_wide_video()==buffer); // Stable allocation, even for frames not drawn expanded.
            CHECK(pk_api_wide_width()==0 && pk_api_wide_height()==0);
            CHECK(!pk_api_wide_skipped());
            CHECK(pk_api_wide_fault()==0);
            CHECK(pk_api_video()!=NULL); // Unknown games retain their ordinary picture.
        }
        pk_api_wide_set_draw(true);
        pk_api_deinit();
        CHECK(pk_api_wide_video()==NULL);
        CHECK(pk_api_wide_width()==0 && pk_api_wide_height()==0);
        CHECK(pk_api_wide_scene()==PK_API_WIDE_SCENE_OFF);
    }
    puts("PASS portable EXPAND bounds, lifetime, stable buffer and unknown-game fallback");
    return 0;
}
