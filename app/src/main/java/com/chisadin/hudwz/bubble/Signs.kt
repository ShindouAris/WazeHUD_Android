package com.chisadin.hudwz.bubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.chisadin.hudwz.domain.HudAlert
import com.chisadin.hudwz.domain.TurnType
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

object Signs {
    private val limitCache = ConcurrentHashMap<Int, Bitmap?>()
    private val assetCache = ConcurrentHashMap<String, Bitmap?>()

    fun loadAsset(context: Context, path: String): Bitmap? {
        if (assetCache.containsKey(path)) return assetCache[path]
        var stream: InputStream? = null
        val bm = try {
            stream = context.assets.open(path)
            BitmapFactory.decodeStream(stream)
        } catch (_: Throwable) {
            null
        } finally {
            try { stream?.close() } catch (_: Throwable) { }
        }
        assetCache[path] = bm
        return bm
    }

    fun limit(context: Context, lim: Int): Bitmap? {
        if (lim < 10 || lim > 120 || lim % 10 != 0) return null
        if (limitCache.containsKey(lim)) return limitCache[lim]
        val path = "speedLimit/speed_limit_$lim.png"
        val bm = loadAsset(context, path)
        limitCache[lim] = bm
        return bm
    }

    fun alert(context: Context, alert: HudAlert): Bitmap? {
        // Special speed drop / limit alert
        if (alert.value != null && alert.value in 10..120) {
            val limitBm = limit(context, alert.value)
            if (limitBm != null) return limitBm
        }
        val file = alertFileName(alert.type) ?: return loadAsset(context, "alerts/bigpin_hazard.png")
        return loadAsset(context, "alerts/$file")
    }

    fun pin(context: Context, name: String): Bitmap? {
        return loadAsset(context, "alerts/$name.png")
    }

    fun turnDrawablePath(turn: TurnType): String? = when (turn) {
        TurnType.NONE -> null
        TurnType.CONTINUE -> "Waze/car_big_trans_direction_forward.png"
        TurnType.LEFT, TurnType.SHARP_LEFT -> "Waze/car_big_trans_direction_left.png"
        TurnType.RIGHT, TurnType.SHARP_RIGHT -> "Waze/car_big_trans_direction_right.png"
        TurnType.SLIGHT_LEFT, TurnType.KEEP_LEFT, TurnType.EXIT_LEFT -> "Waze/car_big_trans_direction_exit_left.png"
        TurnType.SLIGHT_RIGHT, TurnType.KEEP_RIGHT, TurnType.EXIT_RIGHT -> "Waze/car_big_trans_direction_exit_right.png"
        TurnType.U_TURN -> "Waze/car_big_trans_direction_u_turn.png"
        TurnType.U_TURN_RIGHT -> "Waze/car_big_trans_direction_u_turn_lhs.png"
        TurnType.ROUNDABOUT -> "Waze/car_big_trans_directions_roundabout.png"
        TurnType.ROUNDABOUT_LEFT -> "Waze/car_big_trans_directions_roundabout_l.png"
        TurnType.ROUNDABOUT_RIGHT -> "Waze/car_big_trans_directions_roundabout_r.png"
        TurnType.ROUNDABOUT_STRAIGHT -> "Waze/car_big_trans_directions_roundabout_s.png"
        TurnType.ROUNDABOUT_U_TURN -> "Waze/car_big_trans_directions_roundabout_u.png"
        TurnType.ARRIVE -> "Waze/car_big_trans_direction_end.png"
        TurnType.FERRY -> null
    }

    private fun alertFileName(type: Int): String? = when (type) {
        1 -> "bigpin_police.png"
        2 -> "bigpin_speed_camera.png"
        3 -> "bigpin_red_light_camera.png"
        4 -> "bigpin_red_light_and_speed_camera.png"
        5 -> "bigpin_traffic_light.png"
        6 -> "bigpin_hazard.png"
        7 -> "bigpin_accident.png"
        8 -> "bigpin_police_mobile_camera.png"
        9 -> "bigpin_distance_between_vehicles_camera.png"
        10 -> "bigpin_bus_lane_cam.png"
        11 -> "bigpin_railroad.png"
        12 -> "bigpin_permanent_hazard_toll_booth.png"
        13 -> "bigpin_hazard_stopped.png"
        14 -> "bigpin_hazard_construction.png"
        15 -> "bigpin_hazard_pothole.png"
        16 -> "bigpin_bad_weather.png"
        17 -> "bigpin_blocked_lane.png"
        18 -> "bigpin_permanent_hazard_intersection.png"
        19 -> "loi_ra.png"
        20, 21 -> "bigpin_parking.png"
        22, 25 -> "end_of_previous_prohibitions.png"
        23 -> "residential_area_start.png"
        24 -> "residential_area_end.png"
        26, 35 -> "cam_oto.png"
        27, 36 -> "cam_xe_may.png"
        28, 67, 68, 72 -> "no_left_turn.png"
        29, 65, 73 -> "no_right_turn.png"
        30, 74 -> "no_u_turn.png"
        31, 32 -> "only_go_straight.png"
        33 -> "only_turn_right.png"
        34 -> "only_turn_left.png"
        37, 60 -> "bigpin_permanent_hazard_fork.png"
        39, 66, 69 -> "no_left_and_u_turn.png"
        40 -> "bigpin_phone_camera.png"
        41 -> "bigpin_dummy_camera.png"
        42 -> "bigpin_seatbelt_camera.png"
        43 -> "bigpin_distance_between_vehicles_camera.png"
        44 -> "bigpin_bus_lane_cam.png"
        45 -> "bigpin_noise_camera.png"
        46 -> "bigpin_stop_sign_camera.png"
        47 -> "bigpin_animal.png"
        48 -> "bigpin_hazard_object_on_road.png"
        49 -> "bigpin_hazard_roadkill.png"
        50 -> "bigpin_hazard_weather_flood.png"
        51 -> "bigpin_hazard_weather_fog.png"
        52 -> "bigpin_hazard_weather_hail.png"
        53 -> "bigpin_hazard_weather_snow.png"
        54 -> "bigpin_hazard_weather_ice.png"
        55 -> "bigpin_slippery_road.png"
        56 -> "bigpin_permanent_hazard_speed_bumps.png"
        57 -> "bigpin_permanent_hazard_school_zone.png"
        58 -> "bigpin_permanent_hazard_lanes_merging.png"
        59 -> "bigpin_permanent_hazard_dangerous_curves.png"
        61 -> "bigpin_hazard_broken_light.png"
        62 -> "bigpin_cyclist.png"
        63 -> "bigpin_emergency_vehicle.png"
        64 -> "bigpin_personal_safety_a.png"
        70, 71 -> "no_right_and_u_turn.png"
        else -> null
    }
}
