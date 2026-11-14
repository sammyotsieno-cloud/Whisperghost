package com.whispertflite.utils;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** American and British spelling conversion for transcripts. Whole words only; unknown words are left alone. */
public class Spelling {
    private static final Map<String, String> TO_UK = new HashMap<>();
    private static final Map<String, String> TO_US = new HashMap<>();
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    // American, British (a plural "s" form is added automatically)
    private static final String[][] PAIRS = {
        {"center", "centre"}, {"centered", "centred"}, {"centering", "centring"}, {"theater", "theatre"},
        {"liter", "litre"}, {"milliliter", "millilitre"}, {"microliter", "microlitre"}, {"deciliter", "decilitre"},
        {"kilometer", "kilometre"}, {"centimeter", "centimetre"}, {"millimeter", "millimetre"},
        {"fiber", "fibre"}, {"caliber", "calibre"}, {"defense", "defence"}, {"offense", "offence"}, {"pretense", "pretence"},
        {"gray", "grey"}, {"jewelry", "jewellery"}, {"catalog", "catalogue"}, {"pajamas", "pyjamas"},
        {"aluminum", "aluminium"}, {"mold", "mould"}, {"cozy", "cosy"}, {"donut", "doughnut"}, {"airplane", "aeroplane"},
        {"skeptic", "sceptic"}, {"skeptical", "sceptical"}, {"skepticism", "scepticism"}, {"artifact", "artefact"},
        {"aging", "ageing"}, {"acknowledgment", "acknowledgement"}, {"fulfill", "fulfil"}, {"fulfillment", "fulfilment"},
        {"enroll", "enrol"}, {"enrollment", "enrolment"}, {"installment", "instalment"}, {"skillful", "skilful"},
        {"willful", "wilful"}, {"mom", "mum"}, {"mommy", "mummy"}, {"plow", "plough"},
        {"maneuver", "manoeuvre"}, {"maneuvered", "manoeuvred"}, {"maneuvering", "manoeuvring"},
        {"anemia", "anaemia"}, {"anemic", "anaemic"}, {"anesthesia", "anaesthesia"}, {"anesthetic", "anaesthetic"},
        {"esophagus", "oesophagus"}, {"esophageal", "oesophageal"}, {"estrogen", "oestrogen"}, {"estradiol", "oestradiol"},
        {"edema", "oedema"}, {"edematous", "oedematous"}, {"fetus", "foetus"}, {"fetal", "foetal"},
        {"hemoglobin", "haemoglobin"}, {"hemorrhage", "haemorrhage"}, {"hemorrhagic", "haemorrhagic"},
        {"hemorrhoid", "haemorrhoid"}, {"hematology", "haematology"}, {"hematoma", "haematoma"},
        {"hematuria", "haematuria"}, {"hemolysis", "haemolysis"}, {"hemodialysis", "haemodialysis"},
        {"hemodynamic", "haemodynamic"}, {"hemoptysis", "haemoptysis"}, {"hematemesis", "haematemesis"},
        {"hemophilia", "haemophilia"}, {"leukemia", "leukaemia"}, {"leukocyte", "leucocyte"},
        {"leukocytosis", "leucocytosis"}, {"gynecology", "gynaecology"}, {"gynecologist", "gynaecologist"},
        {"gynecological", "gynaecological"}, {"orthopedic", "orthopaedic"}, {"orthopedics", "orthopaedics"},
        {"pediatric", "paediatric"}, {"pediatrics", "paediatrics"}, {"pediatrician", "paediatrician"},
        {"diarrhea", "diarrhoea"}, {"amenorrhea", "amenorrhoea"}, {"dysmenorrhea", "dysmenorrhoea"},
        {"gonorrhea", "gonorrhoea"}, {"dyspnea", "dyspnoea"}, {"orthopnea", "orthopnoea"}, {"apnea", "apnoea"},
        {"tachypnea", "tachypnoea"}, {"ischemia", "ischaemia"}, {"ischemic", "ischaemic"},
        {"septicemia", "septicaemia"}, {"bacteremia", "bacteraemia"}, {"uremia", "uraemia"},
        {"hyperglycemia", "hyperglycaemia"}, {"hypoglycemia", "hypoglycaemia"}, {"hyperglycemic", "hyperglycaemic"},
        {"hypoglycemic", "hypoglycaemic"}, {"glycemic", "glycaemic"}, {"hypoxemia", "hypoxaemia"},
        {"hyperkalemia", "hyperkalaemia"}, {"hypokalemia", "hypokalaemia"}, {"hyponatremia", "hyponatraemia"},
        {"hypernatremia", "hypernatraemia"}, {"hypercalcemia", "hypercalcaemia"},
        {"dyslipidemia", "dyslipidaemia"}, {"hyperlipidemia", "hyperlipidaemia"},
        {"hypercholesterolemia", "hypercholesterolaemia"}, {"ameba", "amoeba"}, {"amebic", "amoebic"},
        {"amebiasis", "amoebiasis"}, {"cesarean", "caesarean"}, {"cecum", "caecum"}, {"feces", "faeces"},
        {"fecal", "faecal"}, {"celiac", "coeliac"}
    };

    // Stems before "ize" (American) / "ise" (British)
    private static final String[] IZE = {
        "organ", "real", "recogn", "summar", "priorit", "minim", "maxim", "author", "categor", "emphas", "familiar",
        "final", "general", "hospital", "immun", "initial", "legal", "memor", "normal", "optim", "special", "standard",
        "stabil", "util", "visual", "critic", "apolog", "capital", "custom", "digit", "equal", "harmon", "item", "local",
        "mobil", "modern", "neutral", "penal", "personal", "public", "rational", "sanit", "social", "sympath", "symbol",
        "terror", "theor", "traumat", "vapor", "civil", "colon", "commercial", "crystal", "demoral", "fertil", "fossil",
        "global", "individual", "industrial", "liberal", "magnet", "material", "mechan", "metabol", "moral", "national",
        "patron", "pasteur", "polar", "popular", "pulver", "radical", "random", "sensit", "steril", "synchron",
        "systemat", "tranquil", "vandal", "vocal", "catheter", "cauter", "central", "computer", "decentral", "desensit",
        "dramat", "econom", "energ", "formal", "ideal", "internation", "jeopard", "marginal", "monopol", "natural",
        "oxid", "plagiar", "privat", "profession", "reorgan", "revolution", "scrutin", "stigmat", "subsid", "urban"
    };
    // Stems before "yze" / "yse"
    private static final String[] YZE = {"anal", "paral", "catal", "dial", "electrol", "hydrol", "breathal"};
    // Stems before "or" (American) / "our" (British)
    private static final String[] OUR = {
        "col", "fav", "hon", "lab", "neighb", "behavi", "flav", "hum", "rum", "vap", "harb", "savi", "od", "parl",
        "rig", "tum", "val", "vig", "arm", "cand", "clam", "endeav", "splend", "demean", "ferv", "ard"
    };
    // Verbs and nouns where British doubles the final l
    private static final String[] LL = {
        "travel", "cancel", "label", "model", "counsel", "signal", "fuel", "level", "channel", "tunnel", "quarrel",
        "marvel", "total", "equal", "jewel", "duel", "libel", "panel"
    };

    private static void add(String us, String uk) {
        if (us.equals(uk)) return;
        TO_UK.put(us, uk);
        TO_US.put(uk, us);
    }

    static {
        for (String[] p : PAIRS) {
            add(p[0], p[1]);
            add(p[0] + "s", p[1] + "s");
        }
        String[] ize = {"ize", "ized", "izes", "izing", "izer", "izers", "ization", "izations"};
        String[] ise = {"ise", "ised", "ises", "ising", "iser", "isers", "isation", "isations"};
        for (String s : IZE) for (int i = 0; i < ize.length; i++) add(s + ize[i], s + ise[i]);
        String[] yze = {"yze", "yzed", "yzes", "yzing", "yzer", "yzers"};
        String[] yse = {"yse", "ysed", "yses", "ysing", "yser", "ysers"};
        for (String s : YZE) for (int i = 0; i < yze.length; i++) add(s + yze[i], s + yse[i]);
        String[] suf = {"", "s", "ed", "ing", "ful", "less", "ite", "ites", "able", "ably", "al", "ally", "ist", "ists", "ism", "hood", "hoods", "er", "ers"};
        for (String s : OUR) for (String x : suf) add(s + "or" + x, s + "our" + x);
        String[] lsuf = {"ed", "ing", "er", "ers", "or", "ors", "ist", "ists"};
        for (String s : LL) for (String x : lsuf) add(s + x, s + "l" + x);
    }

    public static String convertWord(String w, boolean toBritish) {
        String r = (toBritish ? TO_UK : TO_US).get(w.toLowerCase(Locale.ROOT));
        if (r == null) return w;
        if (w.length() > 1 && w.equals(w.toUpperCase(Locale.ROOT))) return r.toUpperCase(Locale.ROOT);
        if (Character.isUpperCase(w.charAt(0))) return Character.toUpperCase(r.charAt(0)) + r.substring(1);
        return r;
    }

    public static String convert(String text, boolean toBritish) {
        Matcher m = WORD.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(convertWord(m.group(), toBritish)));
        m.appendTail(sb);
        return sb.toString();
    }
}
