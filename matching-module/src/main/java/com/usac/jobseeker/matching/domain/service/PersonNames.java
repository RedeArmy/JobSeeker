package com.usac.jobseeker.matching.domain.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconoce nombres de persona en texto libre con listas cerradas de nombres de pila y apellidos
 * hispanos frecuentes (TD-027). Sin modelo de lenguaje: es determinista, se prueba y no sale del
 * proceso.
 *
 * <p>Prima la precisión. Solo cuenta como nombre una racha de palabras con mayúscula que empieza
 * por un nombre de pila conocido y contiene al menos un apellido conocido: «Ana López» sí, «Ana»
 * sola no, «Ruby on Rails» o «San José» tampoco. Lo que no esté en las listas no se reconoce; ese
 * límite queda registrado en TD-027.
 */
final class PersonNames {

  /** Lo que sustituye a un nombre reconocido. */
  static final String PERSON = "[persona]";

  // Racha de 2 a 4 palabras con inicial mayúscula; la clasificación decide si es un nombre.
  private static final Pattern CAPITALIZED_RUN =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}])\\p{Lu}[\\p{L}'’-]++(?:\\s++\\p{Lu}[\\p{L}'’-]++){1,3}+(?![\\p{L}\\p{N}])");

  private static final Pattern SPACES = Pattern.compile("\\s++");

  // Sin «Julia», «Ada» ni «Ruby», que también son lenguajes de programación.
  private static final Set<String> GIVEN =
      Set.of(
          "jose",
          "juan",
          "luis",
          "carlos",
          "jorge",
          "miguel",
          "pedro",
          "mario",
          "manuel",
          "francisco",
          "antonio",
          "fernando",
          "ricardo",
          "roberto",
          "eduardo",
          "javier",
          "alejandro",
          "daniel",
          "david",
          "diego",
          "andres",
          "sergio",
          "raul",
          "oscar",
          "pablo",
          "rafael",
          "hector",
          "hugo",
          "julio",
          "cesar",
          "victor",
          "edgar",
          "erick",
          "kevin",
          "cristian",
          "christian",
          "marvin",
          "byron",
          "walter",
          "rodrigo",
          "gustavo",
          "alberto",
          "enrique",
          "ernesto",
          "gabriel",
          "samuel",
          "mateo",
          "santiago",
          "sebastian",
          "nicolas",
          "emilio",
          "ruben",
          "ramiro",
          "rolando",
          "henry",
          "josue",
          "moises",
          "esteban",
          "gerardo",
          "armando",
          "alfredo",
          "angel",
          "ivan",
          "martin",
          "tomas",
          "maria",
          "ana",
          "laura",
          "carmen",
          "rosa",
          "sofia",
          "lucia",
          "paula",
          "andrea",
          "daniela",
          "gabriela",
          "mariana",
          "valeria",
          "camila",
          "isabel",
          "patricia",
          "claudia",
          "monica",
          "sandra",
          "silvia",
          "elena",
          "teresa",
          "marta",
          "martha",
          "lorena",
          "karla",
          "carla",
          "paola",
          "jessica",
          "jennifer",
          "alejandra",
          "fernanda",
          "natalia",
          "diana",
          "veronica",
          "adriana",
          "beatriz",
          "gloria",
          "irma",
          "leticia",
          "lidia",
          "mayra",
          "mirna",
          "nancy",
          "olga",
          "raquel",
          "susana",
          "ximena",
          "yolanda",
          "wendy",
          "evelyn",
          "ingrid",
          "karen",
          "brenda",
          "cindy",
          "gladys",
          "josefina",
          "juana",
          "luisa",
          "margarita",
          "marisol",
          "pamela",
          "rocio",
          "sara",
          "vanessa");

  private static final Set<String> SURNAMES =
      Set.of(
          "garcia",
          "lopez",
          "perez",
          "gonzalez",
          "rodriguez",
          "hernandez",
          "martinez",
          "sanchez",
          "ramirez",
          "torres",
          "flores",
          "rivera",
          "gomez",
          "diaz",
          "cruz",
          "morales",
          "reyes",
          "ortiz",
          "gutierrez",
          "chavez",
          "ramos",
          "ruiz",
          "mendoza",
          "alvarez",
          "castillo",
          "jimenez",
          "vasquez",
          "velasquez",
          "romero",
          "herrera",
          "medina",
          "aguilar",
          "castro",
          "vargas",
          "guzman",
          "fernandez",
          "munoz",
          "rojas",
          "salazar",
          "mendez",
          "cabrera",
          "estrada",
          "orellana",
          "barrios",
          "cifuentes",
          "monterroso",
          "juarez",
          "pineda",
          "solis",
          "escobar",
          "mejia",
          "santos",
          "ortega",
          "molina",
          "contreras",
          "coronado",
          "recinos",
          "bautista",
          "quiroa",
          "giron",
          "sandoval",
          "marroquin",
          "alvarado",
          "rosales",
          "carrillo",
          "figueroa",
          "navarro",
          "pacheco",
          "valdez",
          "zelaya",
          "ayala",
          "dominguez",
          "nunez",
          "leon",
          "campos",
          "vega",
          "silva",
          "suarez",
          "acosta",
          "rios",
          "cardona",
          "arana",
          "lemus",
          "samayoa",
          "chinchilla",
          "palacios",
          "quinonez",
          "velez",
          "villatoro");

  private PersonNames() {}

  /** El texto con cada nombre reconocido sustituido; `count[0]` suma las sustituciones. */
  static String redact(String text, int[] count) {
    Matcher matcher = CAPITALIZED_RUN.matcher(text);
    StringBuilder out = new StringBuilder(text.length());
    while (matcher.find()) {
      String run = matcher.group();
      String replacement = isPersonName(run) ? PERSON : run;
      if (!replacement.equals(run)) {
        count[0]++;
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  // Empieza por un nombre de pila y lleva al menos un apellido después.
  static boolean isPersonName(String run) {
    String[] words = SPACES.split(run.strip());
    if (words.length < 2 || !GIVEN.contains(plain(words[0]))) {
      return false;
    }
    for (int i = 1; i < words.length; i++) {
      if (SURNAMES.contains(plain(words[i]))) {
        return true;
      }
    }
    return false;
  }

  // Sin tildes ni mayúsculas: «López», «Lopez» y «LÓPEZ» son el mismo apellido.
  private static String plain(String word) {
    return Normalizer.normalize(word, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT);
  }
}
