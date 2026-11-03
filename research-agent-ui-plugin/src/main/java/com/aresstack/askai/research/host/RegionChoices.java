package com.aresstack.askai.research.host;

import com.aresstack.comiccontrols.control.ComicControls;

import javax.swing.JComboBox;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Drop-downs for the country, location and language options of the SERP provider dialogs. Every entry shows a
 * readable English name ("Germany", "German"); the provider code ({@code DE}, {@code 2276}, {@code de}) is
 * stored internally and never has to be typed. A stored code that is not in the list stays selectable as its own
 * entry, so nothing saved is lost. The first entry, "(provider default)", stands for "not set".
 */
final class RegionChoices {

    /** ISO 3166-1 alpha-2 code followed by the three-digit numeric code. */
    private static final String ISO_COUNTRIES = ""
            + "AF004 AX248 AL008 DZ012 AS016 AD020 AO024 AI660 AQ010 AG028 AR032 AM051 AW533 AU036 AT040 AZ031 "
            + "BS044 BH048 BD050 BB052 BY112 BE056 BZ084 BJ204 BM060 BT064 BO068 BQ535 BA070 BW072 BV074 BR076 "
            + "IO086 BN096 BG100 BF854 BI108 CV132 KH116 CM120 CA124 KY136 CF140 TD148 CL152 CN156 CX162 CC166 "
            + "CO170 KM174 CG178 CD180 CK184 CR188 CI384 HR191 CU192 CW531 CY196 CZ203 DK208 DJ262 DM212 DO214 "
            + "EC218 EG818 SV222 GQ226 ER232 EE233 SZ748 ET231 FK238 FO234 FJ242 FI246 FR250 GF254 PF258 TF260 "
            + "GA266 GM270 GE268 DE276 GH288 GI292 GR300 GL304 GD308 GP312 GU316 GT320 GG831 GN324 GW624 GY328 "
            + "HT332 HM334 VA336 HN340 HK344 HU348 IS352 IN356 ID360 IR364 IQ368 IE372 IM833 IL376 IT380 JM388 "
            + "JP392 JE832 JO400 KZ398 KE404 KI296 KP408 KR410 KW414 KG417 LA418 LV428 LB422 LS426 LR430 LY434 "
            + "LI438 LT440 LU442 MO446 MG450 MW454 MY458 MV462 ML466 MT470 MH584 MQ474 MR478 MU480 YT175 MX484 "
            + "FM583 MD498 MC492 MN496 ME499 MS500 MA504 MZ508 MM104 NA516 NR520 NP524 NL528 NC540 NZ554 NI558 "
            + "NE562 NG566 NU570 NF574 MK807 MP580 NO578 OM512 PK586 PW585 PS275 PA591 PG598 PY600 PE604 PH608 "
            + "PN612 PL616 PT620 PR630 QA634 RE638 RO642 RU643 RW646 BL652 SH654 KN659 LC662 MF663 PM666 VC670 "
            + "WS882 SM674 ST678 SA682 SN686 RS688 SC690 SL694 SG702 SX534 SK703 SI705 SB090 SO706 ZA710 GS239 "
            + "SS728 ES724 LK144 SD729 SR740 SJ744 SE752 CH756 SY760 TW158 TJ762 TZ834 TH764 TL626 TG768 TK772 "
            + "TO776 TT780 TN788 TR792 TM795 TC796 TV798 UG800 UA804 AE784 GB826 US840 UM581 UY858 UZ860 VU548 "
            + "VE862 VN704 VG092 VI850 WF876 EH732 YE887 ZM894 ZW716 ";

    static final String NOT_SET = "(provider default)";

    private RegionChoices() {
    }

    /** One drop-down entry: the stored code and the shown name. */
    static final class Choice {
        final String code;
        final String name;

        Choice(String code, String name) {
            this.code = code;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Countries by name; the code is the two-letter ISO code, upper or lower case as the provider expects. */
    static JComboBox<Choice> countries(boolean upperCase) {
        List<Choice> list = new ArrayList<Choice>();
        for (String entry : ISO_COUNTRIES.trim().split(" ")) {
            String alpha2 = entry.substring(0, 2);
            list.add(new Choice(upperCase ? alpha2 : alpha2.toLowerCase(Locale.ROOT), countryName(alpha2)));
        }
        return combo(list);
    }

    /** DataForSEO country locations by name; a country's location code is 2000 plus its ISO numeric code. */
    static JComboBox<Choice> dataForSeoLocations() {
        List<Choice> list = new ArrayList<Choice>();
        for (String entry : ISO_COUNTRIES.trim().split(" ")) {
            int numeric = Integer.parseInt(entry.substring(2));
            list.add(new Choice(String.valueOf(2000 + numeric), countryName(entry.substring(0, 2))));
        }
        return combo(list);
    }

    /** Languages by English name; the code is the two-letter ISO 639-1 code. */
    static JComboBox<Choice> languages() {
        List<Choice> list = new ArrayList<Choice>();
        for (String code : Locale.getISOLanguages()) {
            String name = new Locale(code).getDisplayLanguage(Locale.ENGLISH);
            if (!name.isEmpty() && !name.equalsIgnoreCase(code)) {
                list.add(new Choice(code, name));
            }
        }
        return combo(list);
    }

    /** Language and region pairs ("German (Germany)") stored as a tag such as {@code de-DE}. */
    static JComboBox<Choice> locales() {
        List<Choice> list = new ArrayList<Choice>();
        for (Locale locale : Locale.getAvailableLocales()) {
            if (locale.getLanguage().isEmpty() || locale.getCountry().length() != 2
                    || !locale.getVariant().isEmpty() || !locale.getScript().isEmpty()) {
                continue;
            }
            list.add(new Choice(locale.getLanguage() + "-" + locale.getCountry(),
                    locale.getDisplayLanguage(Locale.ENGLISH) + " (" + locale.getDisplayCountry(Locale.ENGLISH) + ")"));
        }
        return combo(list);
    }

    /** Selects the entry whose code or name matches (ignoring case); an unknown value is added as its own entry. */
    static void select(JComboBox<Choice> combo, String value) {
        if (value == null || value.trim().isEmpty()) {
            combo.setSelectedIndex(0);
            return;
        }
        String wanted = value.trim();
        for (int i = 1; i < combo.getItemCount(); i++) {
            Choice c = combo.getItemAt(i);
            if (c.code.equalsIgnoreCase(wanted) || c.name.equalsIgnoreCase(wanted)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        Choice unknown = new Choice(wanted, wanted);
        combo.insertItemAt(unknown, 1);
        combo.setSelectedIndex(1);
    }

    /** The stored code of the selection, or null for "(provider default)". */
    static String selected(JComboBox<Choice> combo) {
        Object item = combo.getSelectedItem();
        return item instanceof Choice ? ((Choice) item).code : null;
    }

    private static String countryName(String alpha2) {
        String name = new Locale("", alpha2).getDisplayCountry(Locale.ENGLISH);
        return name.isEmpty() ? alpha2 : name;
    }

    private static JComboBox<Choice> combo(List<Choice> list) {
        Collections.sort(list, new Comparator<Choice>() {
            @Override
            public int compare(Choice a, Choice b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        list.add(0, new Choice(null, NOT_SET));
        JComboBox<Choice> combo = ComicControls.comboBox(list.toArray(new Choice[0]));
        combo.setMaximumRowCount(16);
        return combo;
    }
}
