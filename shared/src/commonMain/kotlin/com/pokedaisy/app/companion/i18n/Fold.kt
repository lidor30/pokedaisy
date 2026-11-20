package com.pokedaisy.app.companion.i18n

/**
 * [s] with its accents taken off, for matching typed text: "Pokémon" -> "Pokemon", "définitions" -> "definitions",
 * "ガ" -> "カ", and a Hangul syllable split into its letters ("각" -> "ㄱ ㅏ ㄱ" as conjoining jamo), so "가" finds "각".
 * What java.text.Normalizer's NFD plus dropping the marks gave, in plain Kotlin (the iPad build has no java.text):
 * a table of the Latin and kana letters that decompose (from the JDK's own NFD, `FoldTest` checks it stays equal),
 * Hangul by Unicode's arithmetic, and combining marks dropped.
 */
fun stripAccents(s: String): String = buildString(s.length) {
    for (c in s) {
        val code = c.code
        when {
            code in HANGUL_FIRST..HANGUL_LAST -> {
                val i = code - HANGUL_FIRST
                append((0x1100 + i / (21 * 28)).toChar())
                append((0x1161 + i % (21 * 28) / 28).toChar())
                if (i % 28 != 0) append((0x11A7 + i % 28).toChar())
            }
            isCombiningMark(code) -> {}
            else -> {
                val at = ACCENTED.indexOf(c)
                append(if (at >= 0) PLAIN[at] else c)
            }
        }
    }
}

private const val HANGUL_FIRST = 0xAC00
private const val HANGUL_LAST = 0xD7A3

private fun isCombiningMark(code: Int) =
    code in 0x0300..0x036F || code in 0x1AB0..0x1AFF || code in 0x1DC0..0x1DFF || code in 0x20D0..0x20FF ||
        code in 0xFE20..0xFE2F || code == 0x3099 || code == 0x309A

// Each letter of ACCENTED becomes the one at the same place in PLAIN (Latin-1, Latin Extended-A / B and Additional, kana).
private const val ACCENTED =
    "ÀÁÂÃÄÅÇÈÉÊËÌÍÎÏÑÒÓÔÕÖÙÚÛÜÝàáâãäåçèéêëìíîïñòóôõöùúûüýÿĀāĂăĄąĆćĈĉĊċČčĎďĒēĔĕĖėĘęĚěĜĝĞğĠġĢģĤĥĨ" +
    "ĩĪīĬĭĮįİĴĵĶķĹĺĻļĽľŃńŅņŇňŌōŎŏŐőŔŕŖŗŘřŚśŜŝŞşŠšŢţŤťŨũŪūŬŭŮůŰűŲųŴŵŶŷŸŹźŻżŽžƠơƯưǍǎǏǐǑǒǓǔǕǖǗǘǙǚǛ" +
    "ǜǞǟǠǡǢǣǦǧǨǩǪǫǬǭǮǯǰǴǵǸǹǺǻǼǽǾǿȀȁȂȃȄȅȆȇȈȉȊȋȌȍȎȏȐȑȒȓȔȕȖȗȘșȚțȞȟȦȧȨȩȪȫȬȭȮȯȰȱȲȳḀḁḂḃḄḅḆḇḈḉḊḋḌḍḎḏḐḑ" +
    "ḒḓḔḕḖḗḘḙḚḛḜḝḞḟḠḡḢḣḤḥḦḧḨḩḪḫḬḭḮḯḰḱḲḳḴḵḶḷḸḹḺḻḼḽḾḿṀṁṂṃṄṅṆṇṈṉṊṋṌṍṎṏṐṑṒṓṔṕṖṗṘṙṚṛṜṝṞṟṠṡṢṣṤṥṦṧṨṩṪṫ" +
    "ṬṭṮṯṰṱṲṳṴṵṶṷṸṹṺṻṼṽṾṿẀẁẂẃẄẅẆẇẈẉẊẋẌẍẎẏẐẑẒẓẔẕẖẗẘẙẛẠạẢảẤấẦầẨẩẪẫẬậẮắẰằẲẳẴẵẶặẸẹẺẻẼẽẾếỀềỂểỄễỆệỈỉỊ" +
    "ịỌọỎỏỐốỒồỔổỖỗỘộỚớỜờỞởỠỡỢợỤụỦủỨứỪừỬửỮữỰựỲỳỴỵỶỷỸỹがぎぐげござじずぜぞだぢづでどばぱびぴぶぷべぺぼぽゔゞガギグゲゴザジズゼゾダヂヅデドバ" +
    "パビピブプベペボポヴヷヸヹヺヾ"

private const val PLAIN =
    "AAAAAACEEEEIIIINOOOOOUUUUYaaaaaaceeeeiiiinooooouuuuyyAaAaAaCcCcCcCcDdEeEeEeEeEeGgGgGgGgHhI" +
    "iIiIiIiIJjKkLlLlLlNnNnNnOoOoOoRrRrRrSsSsSsSsTtTtUuUuUuUuUuUuWwYyYZzZzZzOoUuAaIiOoUuUuUuUuU" +
    "uAaAaÆæGgKkOoOoƷʒjGgNnAaÆæØøAaAaEeEeIiIiOoOoRrRrUuUuSsTtHhAaEeOoOoOoOoYyAaBbBbBbCcDdDdDdDd" +
    "DdEeEeEeEeEeFfGgHhHhHhHhHhIiIiKkKkKkLlLlLlLlMmMmMmNnNnNnNnOoOoOoOoPpPpRrRrRrRrSsSsSsSsSsTt" +
    "TtTtTtUuUuUuUuUuVvVvWwWwWwWwWwXxXxYyZzZzZzhtwyſAaAaAaAaAaAaAaAaAaAaAaAaEeEeEeEeEeEeEeEeIiI" +
    "iOoOoOoOoOoOoOoOoOoOoOoOoUuUuUuUuUuUuUuYyYyYyYyかきくけこさしすせそたちつてとははひひふふへへほほうゝカキクケコサシスセソタチツテトハ" +
    "ハヒヒフフヘヘホホウワヰヱヲヽ"
