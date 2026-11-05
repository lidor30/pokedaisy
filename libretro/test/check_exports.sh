#!/bin/sh
# check_exports.sh CORE.so : every libretro entry point is there, and nothing else is exported.
set -e
want="retro_set_environment retro_set_video_refresh retro_set_audio_sample retro_set_audio_sample_batch retro_set_input_poll
retro_set_input_state retro_init retro_deinit retro_api_version retro_get_system_info retro_get_system_av_info
retro_set_controller_port_device retro_reset retro_run retro_serialize_size retro_serialize retro_unserialize
retro_cheat_reset retro_cheat_set retro_load_game retro_load_game_special retro_unload_game retro_get_region
retro_get_memory_data retro_get_memory_size"
have=$(${NM:-nm} -D --defined-only "$1" | awk '{print $3}')
for f in $want; do echo "$have" | grep -qx "$f" || { echo "missing $f" >&2; exit 1; }; done
extra=$(echo "$have" | grep -vx -e retro_.* || true)
[ -z "$extra" ] || { echo "exports besides retro_*: $extra" >&2; exit 1; }
echo "exports ok"
