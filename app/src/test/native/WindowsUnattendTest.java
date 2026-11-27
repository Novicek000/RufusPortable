package pl.rufusportable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public final class WindowsUnattendTest {
    private static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
    private static void check(boolean success,String message) {if(!success)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        WindowsUnattend.Options options=new WindowsUnattend.Options();
        check(WindowsUnattend.create(options).length==0,"no options should create no file");
        options.flags=WindowsUnattend.BYPASS_REQUIREMENTS;
        Document bypass=parse(WindowsUnattend.create(options));
        check(bypass.getElementsByTagName("RunSynchronousCommand").getLength()==3,"three bypass keys");
        check(bypass.getDocumentElement().getTextContent().contains("BypassSecureBootCheck"),"secure boot key");
        options.flags=WindowsUnattend.SET_USER|WindowsUnattend.NO_ONLINE_ACCOUNT|
                WindowsUnattend.NO_DATA_COLLECTION|WindowsUnattend.DISABLE_BITLOCKER|WindowsUnattend.DUPLICATE_LOCALE;
        options.username="  żółć/Jan&Dev  ";options.timezone="Central European Standard Time";
        options.systemLocale="pl-PL";options.userLocale="pl-PL";options.inputLocale="0415:00000415";
        Document account=parse(WindowsUnattend.create(options));
        check(account.getElementsByTagName("Name").item(0).getTextContent().equals("żółć_Jan_Dev"),"username sanitization/UTF-8");
        check(account.getElementsByTagName("Value").item(0).getTextContent().equals("UABhAHMAcwB3AG8AcgBkAA=="),"password encoding");
        check(account.getDocumentElement().getTextContent().contains("BypassNRO"),"offline account");
        check(account.getElementsByTagName("ProtectYourPC").item(0).getTextContent().equals("3"),"privacy selection");
        check(account.getElementsByTagName("PreventDeviceEncryption").getLength()==1,"device encryption");
        check(account.getElementsByTagName("TimeZone").item(0).getTextContent().equals(options.timezone),"Windows timezone input");
        options.username="Administrator";
        check(parse(WindowsUnattend.create(options)).getElementsByTagName("LocalAccount").getLength()==0,"reserved account");
        options.flags=0x11fff;options.username="Test User";options.editionIndex=4;
        for(int arch=1;arch<=4;arch++) {
            options.architecture=arch;
            Document full=parse(WindowsUnattend.create(options));
            check(full.getElementsByTagName("FirstLogonCommands").getLength()==1,"single first-logon section");
            check(full.getElementsByTagName("SanPolicy").item(0).getTextContent().equals("4"),"internal drives offline");
            check(full.getElementsByTagName("SkuPolicyRequired").getLength()==1,"S mode");
            check(full.getElementsByTagName("WillWipeDisk").getLength()==1,"silent install disk configuration");
            Element metadata=(Element)full.getElementsByTagName("MetaData").item(0);
            check(metadata.getElementsByTagName("Key").item(0).getTextContent().equals("/IMAGE/INDEX") &&
                    metadata.getElementsByTagName("Value").item(0).getTextContent().equals("4"),"selected install edition index");
            check(full.getElementsByTagName("InstallTo").item(0).getTextContent().replaceAll("\\s","").equals("03"),"silent install disk 0 partition 3");
            check(full.getDocumentElement().getTextContent().contains("SkuSiPolicy.p7b"),"secure boot policy command");
        }
        options.architecture=0;
        try {WindowsUnattend.create(options);throw new AssertionError("invalid architecture");}catch(IOException expected){}
        options.architecture=2;options.timezone="<invalid>";
        try {WindowsUnattend.create(options);throw new AssertionError("XML injection");}catch(IOException expected){}
        System.out.println("Windows answer-file flags, Unicode, account, privacy, locale and XML tests passed");
    }
}
