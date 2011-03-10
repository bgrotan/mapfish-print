/*
 * Copyright (C) 2009  Camptocamp
 *
 * This file is part of MapFish Server
 *
 * MapFish Server is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MapFish Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with MapFish Server.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mapfish.print;

import com.lowagie.text.*;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfTemplate;

import org.apache.batik.bridge.BridgeContext;
import org.apache.batik.bridge.DocumentLoader;
import org.apache.batik.bridge.GVTBuilder;
import org.apache.batik.bridge.UserAgent;
import org.apache.batik.bridge.UserAgentAdapter;
import org.apache.batik.dom.svg.SAXSVGDocumentFactory;
import org.apache.batik.dom.svg.SVGDocumentFactory;
import org.apache.batik.gvt.GraphicsNode;
import org.apache.batik.util.XMLResourceDescriptor;
import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.cookie.CookiePolicy;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.log4j.Logger;
import org.mapfish.print.config.layout.*;
import org.mapfish.print.utils.PJsonObject;
import org.w3c.dom.svg.SVGDocument;

import java.awt.Graphics2D;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Some utility functions for iText.
 */
public class PDFUtils {
    public static final Logger LOGGER = Logger.getLogger(PDFUtils.class);

    /**
     * Gets an iText image with a cache that uses PdfTemplates to re-use the same
     * bitmap content multiple times in order to reduce the file size.
     */
    public static Image getImage(RenderingContext context, URI uri, float w, float h) throws IOException, DocumentException {
        //Check the image is not already used in the PDF file.
        //
        //This part is not protected against multi-threads... worst case, a single image can
        //be twice in the PDF, if used more than one time. But since only one !map
        //block is dealed with at a time, this should not happen
        Map<URI, PdfTemplate> cache = context.getTemplateCache();
        PdfTemplate template = cache.get(uri);
        if (template == null) {
            Image content = getImageDirect(context, uri);
            content.setAbsolutePosition(0, 0);
            final PdfContentByte dc = context.getDirectContent();
            synchronized (context.getPdfLock()) {  //protect against parallel writing on the PDF file
                template = dc.createTemplate(content.getPlainWidth(), content.getPlainHeight());
                template.addImage(content);
            }
            cache.put(uri, template);
        }

        //fix the size/aspect ratio of the image in function of what is specified by the user
        if (w == 0.0f) {
            if (h == 0.0f) {
                w = template.getWidth();
                h = template.getHeight();
            } else {
                w = h / template.getHeight() * template.getWidth();
            }
        } else {
            if (h == 0.0f) {
                h = w / template.getWidth() * template.getHeight();
            }
        }

        final Image result = Image.getInstance(template);
        result.scaleToFit(w, h);
        return result;
    }

    /**
     * Gets an iText image. Avoids doing the query twice.
     */
    protected static Image getImageDirect(RenderingContext context, URI uri) throws IOException, DocumentException {
        if (!uri.isAbsolute()) {
            //Assumption is that the file is on the local file system
            return Image.getInstance(uri.toString());
        } else if("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getHost() + uri.getPath();
            path = path.replace("/", File.separator);
            return Image.getInstance(new File(path).toURI().toURL());
        } else {
            //read the whole image content in memory, then give that to iText
            GetMethod method = new GetMethod(uri.toString());
            method.getParams().setCookiePolicy(CookiePolicy.IGNORE_COOKIES);
            try {
                if (context.getReferer() != null) {
                    method.setRequestHeader("Referer", context.getReferer());
                }
                if (LOGGER.isDebugEnabled()) LOGGER.debug("loading image: "+uri);
                context.getConfig().getHttpClient(uri).executeMethod(method);
                int code = method.getStatusCode();
                final String contentType;

                Header contentTypeHeader = method.getResponseHeader("Content-Type");
                if(contentTypeHeader == null) {
                    contentType = "";
                } else {
                    contentType = contentTypeHeader.getValue();
                }

                if (code == 204) {
                    // returns a transparent image
                    if (LOGGER.isDebugEnabled()) LOGGER.debug("creating a transparent image for: " + uri);
                    try {
                        byte maskr[] = {(byte)255};
                        Image mask = Image.getInstance(1,1,1,1,maskr);
                        mask.makeMask();
                        byte data[] = new byte[1*1*3];
                        Image image = Image.getInstance(1, 1, 3, 8, data);
                        image.setImageMask(mask);
                        return image;
                    } catch (DocumentException e) {
                        LOGGER.warn("Couldn't generate a transparent image");
                        throw e;
                    }
                } else if (code < 200 || code >= 300 || contentType.startsWith("text/") || contentType.equals("application/vnd.ogc.se_xml")) {
                    if (LOGGER.isDebugEnabled()) LOGGER.debug("Server returned an error for " + uri + ": " + method.getResponseBodyAsString());
                    if (code < 200 || code >= 300) {
                        throw new IOException("Error (status=" + code + ") while reading the image from " + uri + ": " + method.getStatusText());
                    } else {
                        throw new IOException("Didn't receive an image while reading: " + uri);
                    }
                } else {
                    final Image result = Image.getInstance(method.getResponseBody());
                    if (LOGGER.isDebugEnabled()) LOGGER.debug("loaded image: "+uri);
                    return result;
                }
            } catch (IOException e) {
                LOGGER.warn("Server returned an error for " + uri + ": " + e.getMessage());
                throw e;
            } finally {
                method.releaseConnection();
            }
        }
    }

    /**
     * When we have to do some custom drawing in a block that is layed out by
     * iText, we first give an empty table with the good dimensions to iText,
     * then iText will call a callback with the actual position. When that
     * happens, we use the given drawer to do the actual drawing.
     */
    public static PdfPTable createPlaceholderTable(double width, double height, double spacingAfter,
                                                   ChunkDrawer drawer, HorizontalAlign align,
                                                   PDFCustomBlocks customBlocks) {
        PdfPTable placeHolderTable = new PdfPTable(1);
        placeHolderTable.setLockedWidth(true);
        placeHolderTable.setTotalWidth((float) width);
        final PdfPCell placeHolderCell = new PdfPCell();
        placeHolderCell.setMinimumHeight((float) height);
        placeHolderCell.setPadding(0f);
        placeHolderCell.setBorder(PdfPCell.NO_BORDER);
        placeHolderTable.addCell(placeHolderCell);
        customBlocks.addChunkDrawer(drawer);
        placeHolderTable.setTableEvent(drawer);
        placeHolderTable.setComplete(true);

        final PdfPCell surroundingCell = new PdfPCell(placeHolderTable);
        surroundingCell.setPadding(0f);
        surroundingCell.setBorder(PdfPCell.NO_BORDER);
        if (align != null) {
            placeHolderTable.setHorizontalAlignment(align.getCode());
            surroundingCell.setHorizontalAlignment(align.getCode());
        }

        PdfPTable surroundingTable = new PdfPTable(1);
        surroundingTable.setSpacingAfter((float) spacingAfter);
        surroundingTable.addCell(surroundingCell);
        surroundingTable.setComplete(true);

        return surroundingTable;
    }

    private static final Pattern VAR_REGEXP = Pattern.compile("\\$\\{([^}]+)\\}");

    public static Phrase renderString(RenderingContext context, PJsonObject params, String val, com.lowagie.text.Font font) throws BadElementException {
        Phrase result = new Phrase();
        while (true) {
            Matcher matcher = VAR_REGEXP.matcher(val);
            if (matcher.find()) {
                result.add(val.substring(0, matcher.start()));
                final String value;
                final String varName = matcher.group(1);
                if (varName.equals("pageTot")) {
                    result.add(context.getCustomBlocks().getOrCreateTotalPagesBlock(font));
                } else {
                    value = getContextValue(context, params, varName);
                    result.add(value);
                }
                val = val.substring(matcher.end());
            } else {
                break;
            }
        }
        result.add(val);
        return result;
    }

    /**
     * Evaluates stuff like "toto ${titi}"
     */
    public static String evalString(RenderingContext context, PJsonObject params, String val) {
        if (val == null) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        while (true) {
            Matcher matcher = VAR_REGEXP.matcher(val);
            if (matcher.find()) {
                result.append(val.substring(0, matcher.start()));
                result.append(getContextValue(context, params, matcher.group(1)));
                val = val.substring(matcher.end());
            } else {
                break;
            }
        }
        result.append(val);
        String uri = result.toString();

        if(System.getProperty("os.name").toLowerCase().indexOf("win") >= 0){
            uri = uri.replace("\\", "/");
            if(uri.matches("file://\\w:(/.*)?")) {
                return "file:/"+uri.substring(7);
            }
        }

        return uri;
    }

    private static final Pattern FORMAT_PATTERN = Pattern.compile("^format\\s+(%[-+# 0,(]*\\d*(\\.\\d*)?(d))\\s+(.*)$");

    private static String getContextValue(RenderingContext context, PJsonObject params, String key) {
        String result = null;
        if (context != null) {
            Matcher matcher;
            if (key.equals("pageNum")) {
                return Integer.toString(context.getWriter().getPageNumber());
            } else if (key.equals("now")) {
                return new Date().toString();
            } else if (key.startsWith("now ")) {
                return formatTime(context, key);
            } else if ((matcher = FORMAT_PATTERN.matcher(key)) != null && matcher.matches()) {
                return format(context, params, matcher);
            } else if (key.equals("configDir")) {
                return context.getConfigDir().replace('\\', '/');
            } else if (key.equals("scale")) {
                return Integer.toString(context.getLayout().getMainPage().getMap().createTransformer(context, params).getScale());
            }
            result = context.getGlobalParams().optString(key);
        }
        if (result == null) {
            result = params.getString(key);
        }
        return result;
    }

    private static String format(RenderingContext context, PJsonObject params, Matcher matcher) {
        final String valueTxt = getContextValue(context, params, matcher.group(4));
        final Object value;
        switch (matcher.group(3).charAt(0)) {
            case 'd':
            case 'o':
            case 'x':
            case 'X':
                value = Long.valueOf(valueTxt);
                break;
            case 'e':
            case 'E':
            case 'f':
            case 'g':
            case 'G':
            case 'a':
            case 'A':
                value = Double.valueOf(valueTxt);
                break;
            default:
                value = valueTxt;
        }
        try {
            return String.format(matcher.group(1), value);
        } catch (RuntimeException e) {
            // gracefuly fallback to the standard format
            context.addError(e);
            return valueTxt;
        }
    }

    private static String formatTime(RenderingContext context, String key) {
        try {
            SimpleDateFormat format = new SimpleDateFormat(key.substring(4));
            return format.format(new Date());
        } catch (IllegalArgumentException e) {
            // gracefuly fallback to the standard format
            context.addError(e);
            return new Date().toString();
        }
    }

    /**
     * Creates a PDF table with the given items. Returns null if the table is empty
     */
    public static PdfPTable buildTable(List<Block> items, PJsonObject params, RenderingContext context, int nbColumns, TableConfig tableConfig) throws DocumentException {
        int nbCells = 0;
        for (int i = 0; i < items.size(); i++) {
            final Block block = items.get(i);
            if (block.isVisible(context, params)) {
                if (block.isAbsolute()) {
                    // absolute blocks are rendered directly (special case for
                    // header/footer containing absolute blocks; it should not
                    // happen in other usecases).
                    block.render(params, null, context);
                } else {
                    nbCells++;
                }
            }
        }
        if (nbCells == 0) return null;
        nbColumns = nbColumns > 0 ? nbColumns : nbCells;
        int nbRows = (nbCells + nbColumns - 1) / nbColumns;
        final PdfPTable table = new PdfPTable(nbColumns);
        table.setWidthPercentage(100f);

        int cellNum = 0;
        for (int i = 0; i < items.size(); i++) {
            final Block block = items.get(i);
            if (block.isVisible(context, params) && !block.isAbsolute()) {
                final PdfPCell cell = createCell(params, context, block, cellNum / nbColumns, cellNum % nbColumns, nbRows, nbColumns, tableConfig);
                table.addCell(cell);
                cellNum++;
            }
        }
        table.setComplete(true);
        return table;
    }

    /**
     * Create a PDF table cell with support for styling using the {@link org.mapfish.print.config.layout.CellConfig} stuff.
     */
    public static PdfPCell createCell(final PJsonObject params, final RenderingContext context, final Block block, final int row,
                                      final int col, final int nbRows, final int nbCols, final TableConfig tableConfig) throws DocumentException {
        final PdfPCell[] cell = new PdfPCell[1];
        block.render(params, new Block.PdfElement() {
            public void add(Element element) throws DocumentException {
                if (element instanceof PdfPTable) {
                    cell[0] = new PdfPCell((PdfPTable) element);
                } else {
                    final Phrase phrase = new Phrase();
                    phrase.add(element);
                    cell[0] = new PdfPCell(phrase);
                }
                cell[0].setBorder(PdfPCell.NO_BORDER);
                cell[0].setPadding(0);
                if (tableConfig != null) {
                    tableConfig.apply(cell[0], row, col, nbRows, nbCols, context, params);
                }
                if (block.getAlign() != null) {
                    cell[0].setHorizontalAlignment(block.getAlign().getCode());
                }
                if (block.getVertAlign() != null) {
                    cell[0].setVerticalAlignment(block.getVertAlign().getCode());
                }
                if (!(block instanceof MapBlock) && !(block instanceof ScalebarBlock) && block.getBackgroundColorVal(context, params) != null) {
                    cell[0].setBackgroundColor(block.getBackgroundColorVal(context, params));
                }
            }
        }, context);
        return cell[0];
    }

    public static Chunk createImageChunk(RenderingContext context, double maxWidth, double maxHeight, URI url, float rotation) throws DocumentException {
        final Image image = createImage(context, maxWidth, maxHeight, url, rotation);
        return new Chunk(image, 0f, 0f, true);
    }

    public static Image createImage(RenderingContext context, double maxWidth, double maxHeight, URI url, float rotation) throws DocumentException {
        final Image image;
        try {
            image = getImage(context, url, (float) maxWidth, (float) maxHeight);
        } catch (IOException e) {
            throw new InvalidValueException("url", url.toString(), e);
        }

        if (rotation != 0.0F) {
            image.setRotation(rotation);
        }
        return image;
    }

	public static BaseFont getBaseFont(String fontFamily, String fontSize,
			String fontWeight) {
		int myFontValue;
		float myFontSize;
		int myFontWeight;
		if (fontFamily.toUpperCase().contains("COURIER")) {
			myFontValue = Font.COURIER;
		} else if (fontFamily.toUpperCase().contains("HELVETICA")) {
			myFontValue = Font.HELVETICA;
		} else if (fontFamily.toUpperCase().contains("ROMAN")) {
			myFontValue = Font.TIMES_ROMAN;
		} else {
			myFontValue = Font.HELVETICA;
		}
		myFontSize = (float) Double.parseDouble(fontSize.toLowerCase()
				.replaceAll("px", ""));
		if (fontWeight.toUpperCase().contains("NORMAL")) {
			myFontWeight = Font.NORMAL;
		} else if (fontWeight.toUpperCase().contains("BOLD")) {
			myFontWeight = Font.BOLD;
		} else if (fontWeight.toUpperCase().contains("ITALIC")) {
			myFontWeight = Font.ITALIC;
		} else {
			myFontWeight = Font.NORMAL;
		}
		Font pdfFont = new Font(myFontValue, myFontSize, myFontWeight);
		BaseFont bf = pdfFont.getCalculatedBaseFont(false);
		return bf;
	}

	public static int getHorizontalAlignment(String labelAlign) {
		/* Valid values for horizontal alignment: "l"=left, "c"=center, "r"=right. */
		int myAlignment = PdfContentByte.ALIGN_LEFT;
		if (labelAlign.toUpperCase().contains("L")) {
			myAlignment = PdfContentByte.ALIGN_LEFT;
		}
		if (labelAlign.toUpperCase().contains("C")) {
			myAlignment = PdfContentByte.ALIGN_CENTER;
		}
		if (labelAlign.toUpperCase().contains("R")) {
			myAlignment = PdfContentByte.ALIGN_RIGHT;
		}
		return myAlignment;
	}

	public static float getVerticalOffset(String labelAlign, float fontHeight) {
		/* Valid values for vertical alignment: "t"=top, "m"=middle, "b"=bottom. */
		float myOffset = (float) 0.0;
		if (labelAlign.toUpperCase().contains("T")) {
			myOffset = fontHeight;
		}
		if (labelAlign.toUpperCase().contains("M")) {
			myOffset = fontHeight/2;
		}
		if (labelAlign.toUpperCase().contains("B")) {
			myOffset = (float) 0.0;
		}
		return myOffset;
	}

	public static Image createImageFromSVG(RenderingContext context, String iconItem, double maxIconWidth, double maxIconHeight) throws IOException {
        Image image = null;
        try {
            PdfContentByte dc = context.getDirectContent();
            URI uri = URI.create(iconItem);
            URL url = uri.toURL();
            SVGDocumentFactory factory = new SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName());
            UserAgent userAgent = new UserAgentAdapter();
            DocumentLoader loader = new DocumentLoader(userAgent);
            BridgeContext ctx = new BridgeContext(userAgent, loader);
            ctx.setDynamicState(BridgeContext.DYNAMIC);
            SVGDocument svgDoc = factory.createSVGDocument(null, url.openStream());
            GVTBuilder builder = new GVTBuilder();
            GraphicsNode graphics = builder.build(ctx, svgDoc);
            String svgWidthString = svgDoc.getDocumentElement().getAttribute("width");
            String svgHeightString = svgDoc.getDocumentElement().getAttribute("height");
            float svgWidth = Float.valueOf(svgWidthString.substring(0, svgWidthString.length() - 2));
            float svgHeight = Float.valueOf(svgHeightString.substring(0, svgHeightString.length() - 2));
            /**
             * svgFactor needs to be calculated depending on the screen DPI by the PDF DPI
             * This is 96 / 72 = 4 / 3 ~= 1.3333333 on Windows, but might be different on *nix.
             */
            final float svgFactor = 25.4f / userAgent.getPixelUnitToMillimeter() / 72f; // 25.4 mm = 1 inch TODO: Might need to get 72 from somewhere else?
            //float svgFactor = (float) Toolkit.getDefaultToolkit().getScreenResolution() / 72f; // this only works with AWT, i.e. when a window environment is running
            PdfTemplate map = dc.createTemplate(svgWidth * svgFactor, svgHeight * svgFactor);
            Graphics2D g2d = map.createGraphics(svgWidth * svgFactor, svgHeight * svgFactor);
            graphics.paint(g2d);
            g2d.dispose();
            image = Image.getInstance(map);
            image.scaleToFit((float) maxIconWidth, (float) maxIconHeight);
        } catch (BadElementException bee) {
            LOGGER.warn("Bad Element " + iconItem + " with " + bee.getMessage());
        } catch (MalformedURLException mue) {
            LOGGER.warn("Malformed URL " + iconItem + " with " + mue.getMessage());
        } catch (IOException ioe) {
            throw ioe;
        }
        return image;
    }
}
