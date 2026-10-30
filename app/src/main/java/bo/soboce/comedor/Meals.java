package bo.soboce.comedor;

import java.util.*;

/** Diet is a lunch alternative, with the same daily allowance and price. */
final class Meals {
    static final List<String> ALL=Collections.unmodifiableList(Arrays.asList("desayuno","almuerzo","cena","dieta"));
    static String allowance(String meal){return meal.equals("dieta")?"almuerzo":meal;}
    static boolean permitted(String allowed,String meal){List<String> list=Arrays.asList(allowed.split(","));return list.contains(meal)||(meal.equals("dieta")&&list.contains("almuerzo"));}
    static String title(String meal){return meal.isEmpty()?"Comida":meal.substring(0,1).toUpperCase(Locale.ROOT)+meal.substring(1);}
}
