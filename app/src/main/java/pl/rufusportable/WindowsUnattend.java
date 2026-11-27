package pl.rufusportable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Rufus Windows User Experience flags and its native wue.c XML generator. */
final class WindowsUnattend {
    private static final class Native {
        static {System.loadLibrary("rufus_wim");}
        static void load() {}
    }
    static final int BYPASS_REQUIREMENTS=0x00001,NO_ONLINE_ACCOUNT=0x00004,NO_DATA_COLLECTION=0x00008,
            OFFLINE_INTERNAL_DRIVES=0x00010,DUPLICATE_LOCALE=0x00020,SET_USER=0x00040,
            DISABLE_BITLOCKER=0x00080,FORCE_S_MODE=0x00100,USE_MS2023_BOOTLOADERS=0x00200,
            APPLY_SKUSIPOLICY=0x00400,SILENT_INSTALL=0x00800,QOL_ENHANCEMENTS=0x01000,
            WINDOWS_TO_GO=0x10000;
    static final class Options {
        int architecture=2,flags,editionIndex=1;
        String username="",timezone="UTC",inputLocale="en-US",systemLocale="en-US",
                userLocale="en-US",uiLanguage="en-US",fallbackLanguage="en-US",setupLanguage="en-US";
    }
    private static byte[][] fields(Options options) throws IOException {
        if(options.architecture<1 || options.architecture>4 || options.editionIndex<1 ||
                (options.flags&~0x11fff)!=0) throw new IOException("Nieprawidłowe opcje instalacji Windows.");
        String[] fields={options.username,options.timezone,options.inputLocale,options.systemLocale,
                options.userLocale,options.uiLanguage,options.fallbackLanguage,options.setupLanguage};
        byte[][] values=new byte[fields.length][];
        for(int i=0;i<fields.length;i++) {
            String value=fields[i];
            if(value==null || value.chars().anyMatch(c->Character.isISOControl(c)))
                throw new IOException("Nieprawidłowy znak w ustawieniach Windows.");
            if(i!=0 && (value.indexOf('&')>=0 || value.indexOf('<')>=0 || value.indexOf('>')>=0))
                throw new IOException("Nieprawidłowa wartość języka lub strefy czasowej Windows.");
            values[i]=value.getBytes(StandardCharsets.UTF_8);
        }
        if(values[0].length>=128) throw new IOException("Nazwa użytkownika jest za długa.");
        return values;
    }
    static String sanitizeUsername(String username) {
        StringBuilder clean=new StringBuilder();
        String invalid="/\\[]:;|=,+*?<>%@&\"";
        for(char value:username.trim().toCharArray())clean.append(invalid.indexOf(value)>=0?'_':value);
        return clean.toString();
    }
    static boolean validUsername(String username) {
        String value=sanitizeUsername(username);
        if(value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length>=128 ||
                value.chars().anyMatch(Character::isISOControl))return false;
        for(String reserved:new String[]{"Administrator","Järjestelmänvalvoja","Administrateur","Rendszergazda","Administrador",
                "Администратор","Administratör","Guest","DefaultAccount","WDAGUtilityAccount","HelpAssistant","KRBTGT","Local","NONE","SYSTEM"})
            if(value.equalsIgnoreCase(reserved))return false;
        return true;
    }
    static void validate(Options options) throws IOException {fields(options);}
    static byte[] create(Options options) throws IOException {
        byte[][] values=fields(options);
        if(options.flags==0) return new byte[0];
        Native.load();
        return generate(options.architecture,options.flags,options.editionIndex,values);
    }
    private static native byte[] generate(int architecture,int flags,int edition,byte[][] values) throws IOException;
}
