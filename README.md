# Inventeringshjälpen

Android-app som räknar saker i ett foto: papprör, wellpappskivor, skruvar, O-ringar och annat.

## Så funkar den

1. **Välj vad du ska räkna**. Varje materialtyp har en räknemetod:
   - *Runda ändar – ihåliga*: rör och hylsor. Du drar en ruta runt rören.
   - *Runda ändar – massiva*: stång, stock och rullar. Du drar en ruta.
   - *Lösa småsaker*: skruv, mutter, bricka, O-ring som ligger huller om buller i en låda. Du drar en ruta innanför lådans kanter.
   - *Skivor i stapel*: wellpapp, skivor och lager sedda från sidan. Du drar en linje tvärs över stapeln.
   - *Manuellt*: du trycker på varje sak.
   Ange gärna *antal per markering*, t.ex. 10 om varje markering är en bunt om 10. Du kan också ange standard-artikelnummer och standard-benämning.
2. **Ta foto** eller **välj bild**. Du kan också dela en bild till appen från galleriet.
3. Appen sätter ett numrerat märke på varje sak den hittar. Orange betyder hittad automatiskt och blått betyder tillagd av dig.
4. **Rätta** genom att trycka: på en tom plats läggs ett märke till och på ett märke tas det bort. Du nyper för att zooma och drar för att flytta. *Ångra* finns.
5. **Klar** visar resultatet. Artikelnummer, batch, benämning och lagerplats läses från etiketten om den syns i bilden. Annars trycker du *Fota etikett* eller skriver in uppgifterna själv.
6. **Spara** lägger räkningen i historiken. **Dela** skickar texten och en bild med alla märken, t.ex. via mejl eller Teams. Historiken kan exporteras som CSV till Excel.

## Tips för säkrare räkning

- Fota rakt framifrån och fyll bilden med det som ska räknas.
- Skivor: appen läser remsan kring linjen i full upplösning från originalfotot. Räknar den varannan skiva eller dubbelt trycker du **Tätare** eller **Glesare**. Gå hellre nära. Varje skiva bör vara minst 8–10 pixlar tjock i bilden. En hel pall på håll räcker inte. Fota i stället en bunt i taget och tryck **Klar → + Lägg till del**, så summeras delarna. Varje skiva bör vara minst ca 10 pixlar tjock i bilden. Räkna hellre en stapel i två delar än en hel stapel på långt håll.
- Lösa småsaker: lägg dem på ett jämnt underlag med tydlig kontrast, t.ex. svarta O-ringar på vitt papper och blanka skruvar på kartong. Undvik hårda skuggor. Sprid ut högar lite. Djupa högar där saker döljer varandra går inte att räkna från ett foto.
- Ligger sakerna i klungor (t.ex. karameller med vridna ändar) trycker du **Visa en sak** och drar en tät ruta runt en enda sak, gärna mitt i bilden. Då vet appen exakt hur stor en sak är.
- Om appen missar många kan du höja *känsligheten* och trycka *Räkna om*. Om den hittar för mycket sänker du känsligheten.

## Bygga

Ladda upp allt i den här mappen till ett GitHub-repo. Mappen `.github/workflows` måste komma med. GitHub Actions bygger då en APK som du hittar under **Actions → senaste körningen → Artifacts → Inventeringshjalpen-apk**.

Appen signeras med en fast nyckel (`app/debug.keystore`), så nya versioner kan installeras ovanpå gamla.
