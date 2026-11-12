package io.github.aliihjt.tabletdesk;

/**
 * The Iranian (Jalali) calendar: today's date in Persian, Iran's holidays and the next one.
 * Solar holidays are exact; lunar (religious) ones follow the Umm al-Qura Islamic calendar a
 * day later, as Iran's calendar usually runs, but Iran can differ by a day, so they are marked "≈".
 * The date maths is the jalaali-js algorithm (Borkowski), the same years Iran uses.
 */
final class PersianCalendar {
    static final String[] MONTHS = {"فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"};
    /** java.util.Calendar.DAY_OF_WEEK 1 (Sunday) .. 7 (Saturday). */
    static final String[] WEEKDAYS = {"", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه"};

    private static final int[] BREAKS = {-61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
            1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178};

    // {jalali month, day, name}
    private static final Object[][] SOLAR = {
            {1, 1, "عید نوروز"}, {1, 2, "عید نوروز"}, {1, 3, "عید نوروز"}, {1, 4, "عید نوروز"},
            {1, 12, "روز جمهوری اسلامی"}, {1, 13, "روز طبیعت"}, {3, 14, "رحلت امام خمینی"},
            {3, 15, "قیام ۱۵ خرداد"}, {11, 22, "پیروزی انقلاب اسلامی"}, {12, 29, "ملی شدن صنعت نفت"},
    };
    // {hijri month, day, name}; day 30 of Safar = its last day
    private static final Object[][] LUNAR = {
            {1, 9, "تاسوعا"}, {1, 10, "عاشورا"}, {2, 20, "اربعین"}, {2, 28, "رحلت پیامبر و شهادت امام حسن"},
            {2, 30, "شهادت امام رضا"}, {3, 8, "شهادت امام حسن عسکری"}, {3, 17, "میلاد پیامبر"},
            {6, 3, "شهادت حضرت فاطمه"}, {7, 13, "ولادت امام علی"}, {7, 27, "مبعث"},
            {8, 15, "ولادت امام زمان"}, {9, 21, "شهادت امام علی"}, {10, 1, "عید فطر"}, {10, 2, "عید فطر"},
            {10, 25, "شهادت امام صادق"}, {12, 10, "عید قربان"}, {12, 18, "عید غدیر"},
    };

    private PersianCalendar() {
    }

    // ---- Gregorian <-> Jalali (jalaali-js; / and % truncate like JavaScript's ~~)

    static int[] toJalali(int gy, int gm, int gd) {
        return d2j(g2d(gy, gm, gd));
    }

    private static int[] jalCal(int jy) {  // {leap, gy, march}
        int gy = jy + 621, leapJ = -14, jp = BREAKS[0], jump = 0;
        for (int i = 1; i < BREAKS.length; i++) {
            int jm = BREAKS[i];
            jump = jm - jp;
            if (jy < jm) break;
            leapJ = leapJ + jump / 33 * 8 + (jump % 33) / 4;
            jp = jm;
        }
        int n = jy - jp;
        leapJ = leapJ + n / 33 * 8 + ((n % 33) + 3) / 4;
        if (jump % 33 == 4 && jump - n == 4) leapJ += 1;
        int leapG = gy / 4 - ((gy / 100 + 1) * 3) / 4 - 150;
        int march = 20 + leapJ - leapG;
        if (jump - n < 6) n = n - jump + ((jump + 4) / 33) * 33;
        int leap = (((n + 1) % 33) - 1) % 4;
        if (leap == -1) leap = 4;
        return new int[]{leap, gy, march};
    }

    private static int[] d2j(int jdn) {
        int gy = d2g(jdn)[0], jy = gy - 621;
        int[] r = jalCal(jy);
        int k = jdn - g2d(gy, 3, r[2]);
        if (k >= 0) {
            if (k <= 185) return new int[]{jy, 1 + k / 31, k % 31 + 1};
            k -= 186;
        } else {
            jy -= 1;
            k += 179;
            if (r[0] == 1) k += 1;
        }
        return new int[]{jy, 7 + k / 30, k % 30 + 1};
    }

    private static int g2d(int gy, int gm, int gd) {
        int d = ((gy + (gm - 8) / 6 + 100100) * 1461) / 4 + (153 * ((gm + 9) % 12) + 2) / 5 + gd - 34840408;
        return d - (((gy + 100100 + (gm - 8) / 6) / 100) * 3) / 4 + 752;
    }

    private static int[] d2g(int jdn) {
        int j = 4 * jdn + 139361631;
        j = j + (((4 * jdn + 183187720) / 146097) * 3) / 4 * 4 - 3908;
        int i = ((j % 1461) / 4) * 5 + 308;
        int gd = (i % 153) / 5 + 1, gm = (i / 153) % 12 + 1;
        return new int[]{j / 1461 - 100100 + (8 - gm) / 6, gm, gd};
    }

    // ---- words

    static String digits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) b.append(c >= '0' && c <= '9' ? (char) ('۰' + (c - '0')) : c);
        return b.toString();
    }

    /** "چهارشنبه ۲۱ آبان ۱۴۰۵" */
    static String format(java.util.Calendar day) {
        int[] j = toJalali(day.get(java.util.Calendar.YEAR), day.get(java.util.Calendar.MONTH) + 1,
                day.get(java.util.Calendar.DAY_OF_MONTH));
        return WEEKDAYS[day.get(java.util.Calendar.DAY_OF_WEEK)] + " " + digits(String.valueOf(j[2])) + " "
                + MONTHS[j[1] - 1] + " " + digits(String.valueOf(j[0]));
    }

    /** The day's holiday, or "" ("≈" before a lunar one). */
    static String holiday(java.util.Calendar day) {
        int[] j = toJalali(day.get(java.util.Calendar.YEAR), day.get(java.util.Calendar.MONTH) + 1,
                day.get(java.util.Calendar.DAY_OF_MONTH));
        for (Object[] h : SOLAR) {
            if ((int) h[0] == j[1] && (int) h[1] == j[2]) return (String) h[2];
        }
        int[] hj = hijri(day);
        if (hj == null) return "";
        for (Object[] h : LUNAR) {
            int m = (int) h[0], d = (int) h[1];
            boolean lastOfSafar = m == 2 && d == 30 && hj[0] == 2 && hj[1] == hj[2];
            if ((m == hj[0] && d == hj[1]) || lastOfSafar) return "≈ " + h[2];
        }
        return "";
    }

    /** {hijri month, day, days in that month} as Iran counts them: Umm al-Qura one day later (Ashura
     * and both Eids in 2025 fell a day after it in Iran); null if Android can't tell. */
    private static int[] hijri(java.util.Calendar day) {
        try {
            android.icu.util.IslamicCalendar c = new android.icu.util.IslamicCalendar();
            c.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
            c.setTimeInMillis(day.getTimeInMillis() - 86_400_000L);
            return new int[]{c.get(android.icu.util.Calendar.MONTH) + 1, c.get(android.icu.util.Calendar.DAY_OF_MONTH),
                    c.getActualMaximum(android.icu.util.Calendar.DAY_OF_MONTH)};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "عید فطر · ۳ روز دیگر" for the next holiday within 60 days, or "". */
    static String nextHoliday(java.util.Calendar today) {
        java.util.Calendar d = (java.util.Calendar) today.clone();
        for (int i = 1; i <= 60; i++) {
            d.add(java.util.Calendar.DAY_OF_MONTH, 1);
            String h = holiday(d);
            if (!h.isEmpty()) return h + " · " + (i == 1 ? "فردا" : digits(String.valueOf(i)) + " روز دیگر");
        }
        return "";
    }
}
