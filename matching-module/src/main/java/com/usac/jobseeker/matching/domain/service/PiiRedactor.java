package com.usac.jobseeker.matching.domain.service;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quita de un texto libre lo que identifica o permite contactar a una persona, antes de que llegue
 * a un modelo de embeddings (ADR-0017, decisión 2 y riesgo R5; BR-DATA-01). Excluir campos por
 * nombre no basta: una descripción de experiencia puede traer un correo, un teléfono o un enlace a
 * un perfil.
 *
 * <p>Qué redacta: correos, enlaces (con esquema, con `www.` o a redes y repositorios conocidos),
 * usuarios con `@`, secuencias de cifras con aspecto de teléfono o de documento de identidad, y
 * nombres de persona hispanos frecuentes ({@link PersonNames}).
 *
 * <p>Límites conocidos: un nombre fuera de las listas de {@link PersonNames} no se reconoce
 * (TD-027), porque reconocer cualquier nombre exige un modelo de lenguaje. Tampoco distingue
 * siempre una cifra de contacto de otra parecida: prefiere redactar de más (puede borrar un rango
 * salarial escrito como un teléfono, «3000-4500») que dejar pasar un teléfono, porque lo primero
 * cuesta algo de significado y lo segundo, un dato personal en un servicio de terceros.
 *
 * <p>Todas las expresiones usan cuantificadores acotados o posesivos: el costo es lineal en el
 * texto, sin retroceso catastrófico ante una entrada hostil.
 */
public final class PiiRedactor {

  public static final String EMAIL = "[correo]";
  public static final String LINK = "[enlace]";
  public static final String USER = "[usuario]";
  public static final String PHONE = "[teléfono]";
  public static final String DOCUMENT = "[documento]";
  public static final String PERSON = PersonNames.PERSON;

  /** El texto ya redactado y cuántas piezas se quitaron (sin decir cuáles). */
  public record Result(String text, int redactions) {}

  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("[\\p{L}\\p{N}._%+-]++@[\\p{L}\\p{N}-]++(?:\\.[\\p{L}\\p{N}-]++)++");

  private static final String PATH = "[^\\s<>\"')\\]]*+";

  // Sitios cuyos enlaces son perfiles de personas, aun sin esquema (`github.com/ana`).
  private static final List<String> PROFILE_HOSTS =
      List.of(
          "linkedin\\.com",
          "github\\.com",
          "gitlab\\.com",
          "bitbucket\\.com",
          "twitter\\.com",
          "facebook\\.com",
          "instagram\\.com",
          "behance\\.com",
          "dribbble\\.com",
          "medium\\.com",
          "stackoverflow\\.com",
          "x\\.com",
          "t\\.me",
          "wa\\.me");

  private static final Pattern LINK_WITH_SCHEME =
      Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>\"')\\]]++");

  // La subcadena de etiquetas va perezosa y acotada (0 a 3), no posesiva: una posesiva se comería
  // `github.` como si fuera un subdominio y ya no podría reconocer el sitio.
  private static final Pattern LINK_TO_PROFILE =
      Pattern.compile(
          "(?i)\\b(?:[a-z0-9-]++\\.){0,3}?(?:" + String.join("|", PROFILE_HOSTS) + ")/" + PATH);

  private static final Pattern USER_PATTERN = Pattern.compile("(?<![\\p{L}\\p{N}_@])@\\w{3,30}+");

  // Una racha de cifras con, a lo sumo, dos separadores entre cada par (así cabe «(502)
  // 5555-1234»).
  // Posesiva y sin tope superior: toma la racha entera y cuánto mide lo decide la clasificación; el
  // separador y la cifra son disjuntos, así que el costo es lineal.
  private static final Pattern NUMBER_PATTERN =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}+])\\(?\\+?\\d(?:[\\s().-]{0,2}+\\d){7,}+(?![\\p{L}\\p{N}])");

  private static final int PHONE_MIN_DIGITS = 8;
  private static final int DOCUMENT_DIGITS = 13;
  private static final int YEAR_MIN = 1950;
  private static final int YEAR_MAX = 2100;
  private static final int YEAR_DIGITS = 4;

  private PiiRedactor() {}

  public static Result redact(String raw) {
    if (raw == null || raw.isEmpty()) {
      return new Result("", 0);
    }
    int[] count = {0};
    String text = replace(EMAIL_PATTERN, raw.replace('\u0000', ' '), count, m -> EMAIL);
    text = replace(LINK_WITH_SCHEME, text, count, m -> LINK);
    text = replace(LINK_TO_PROFILE, text, count, m -> LINK);
    text = replace(USER_PATTERN, text, count, m -> USER);
    text = replace(NUMBER_PATTERN, text, count, PiiRedactor::numberReplacement);
    // TD-027: los nombres de persona, al final, cuando ya no quedan correos ni enlaces que los
    // contengan.
    text = PersonNames.redact(text, count);
    return new Result(text, count[0]);
  }

  private static String replace(
      Pattern pattern, String input, int[] count, UnaryOperator<String> replacement) {
    Matcher matcher = pattern.matcher(input);
    StringBuilder out = new StringBuilder(input.length());
    while (matcher.find()) {
      String found = matcher.group();
      String substitute = replacement.apply(found);
      if (!substitute.equals(found)) {
        count[0]++;
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(substitute));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  // Un rango de años («2018-2022», «2019 2021») tiene la forma de un teléfono pero no lo es: es lo
  // más frecuente que escribe quien describe su experiencia.
  private static String numberReplacement(String found) {
    String[] groups = found.replaceAll("\\D+", " ").strip().split(" ");
    boolean onlyYears = groups.length >= 2;
    for (String group : groups) {
      onlyYears = onlyYears && isYear(group);
    }
    if (onlyYears) {
      return found;
    }
    int digits = found.replaceAll("\\D", "").length();
    if (digits < PHONE_MIN_DIGITS) {
      return found;
    }
    return digits >= DOCUMENT_DIGITS ? DOCUMENT : PHONE;
  }

  private static boolean isYear(String group) {
    if (group.length() != YEAR_DIGITS) {
      return false;
    }
    int year = Integer.parseInt(group);
    return year >= YEAR_MIN && year <= YEAR_MAX;
  }
}
