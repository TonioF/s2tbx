package org.esa.s2tbx.fcc;

import com.bc.ceres.binding.Property;
import com.bc.ceres.binding.PropertyContainer;
import com.bc.ceres.binding.PropertyDescriptor;
import com.bc.ceres.binding.PropertySet;
import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.swing.TableLayout;
import com.bc.ceres.swing.binding.BindingContext;
import com.bc.ceres.swing.binding.PropertyEditor;
import com.bc.ceres.swing.binding.PropertyEditorRegistry;
import com.bc.ceres.swing.binding.PropertyPane;
import com.bc.ceres.swing.progress.ProgressMonitorSwingWorker;
import com.bc.ceres.swing.selection.SelectionChangeEvent;
import com.bc.ceres.swing.selection.SelectionChangeListener;
import org.esa.s2tbx.fcc.annotation.ParameterGroup;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Mask;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductManager;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.SourceProducts;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.gpf.annotations.TargetProperty;
import org.esa.snap.core.gpf.descriptor.AnnotationParameterDescriptor;
import org.esa.snap.core.gpf.descriptor.AnnotationSourceProductDescriptor;
import org.esa.snap.core.gpf.descriptor.AnnotationTargetPropertyDescriptor;
import org.esa.snap.core.gpf.descriptor.OperatorDescriptor;
import org.esa.snap.core.gpf.descriptor.ParameterDescriptor;
import org.esa.snap.core.gpf.descriptor.SourceProductDescriptor;
import org.esa.snap.core.gpf.descriptor.TargetPropertyDescriptor;
import org.esa.snap.core.gpf.ui.DefaultIOParametersPanel;
import org.esa.snap.core.gpf.ui.OperatorMenu;
import org.esa.snap.core.gpf.ui.OperatorParameterSupport;
import org.esa.snap.core.gpf.ui.SingleTargetProductDialog;
import org.esa.snap.core.gpf.ui.SourceProductSelector;
import org.esa.snap.core.gpf.ui.TargetProductSelector;
import org.esa.snap.core.gpf.ui.TargetProductSelectorModel;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.rcp.SnapApp;
import org.esa.snap.rcp.actions.file.SaveProductAsAction;
import org.esa.snap.ui.AppContext;
import org.esa.snap.utils.StringHelper;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.border.EmptyBorder;
import java.awt.Component;
import java.awt.Toolkit;
import java.io.File;
import java.lang.reflect.Field;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;

import static com.bc.ceres.swing.TableLayout.cell;

/**
 * @author Razvan Dumitrascu
 * @since 5.0.6
 */
public class ForestCoverChangeTargetProductDialog extends SingleTargetProductDialog {

    private static final String RECENT_PRODUCT_PROPERTY = "recentProduct";
    private static final String PREVIOUS_PRODUCT_PROPERTY = "previousProduct";
    private static final String LAND_COVER_EXTERNAL_FILE_PROPERTY ="landCoverExternalFile";
    private static final String LAND_COVER_MAP_INDICES_PROPERTY = "landCoverMapIndices";
    private static final String CURRENT_PRODUCT_SOURCE_MASK = "currentProductSourceMaskFile";
    private static final String PREVIOUS_PRODUCT_SOURCE_MASK = "previousProductSourceMaskFile";

    private static final int CURRENT_PRODUCT = 0;
    private static final int PREVIOUS_PRODUCT = 1;

    private final String operatorName;
    private final OperatorDescriptor operatorDescriptor;
    private final OperatorParameterSupport parameterSupport;
    private final BindingContext bindingContext;
    private final DefaultIOParametersPanel ioParametersPanel;
    private List<ParameterDescriptor> parameterDescriptors;
    private List<SourceProductDescriptor> sourceProductDescriptors;
    private List<TargetPropertyDescriptor> targetPropertyDescriptors;
    private Map<String, List<String>> parameterGroupDescriptors;
    private JTabbedPane form;
    private String targetProductNameSuffix;


    public ForestCoverChangeTargetProductDialog(String operatorName, AppContext appContext, String title, String helpID) {
        super(appContext, title, ID_APPLY_CLOSE, helpID);

        this.operatorName = operatorName;
        this.targetProductNameSuffix = "";
        final TargetProductSelector selector = getTargetProductSelector();
        selector.getModel().setSaveToFileSelected(false);
        selector.getSaveToFileCheckBox().setEnabled(true);
        processAnnotationsRec(ForestCoverChangeOp.class);
        this.operatorDescriptor = new OperatorDescriptorClass( this.parameterDescriptors.toArray(new ParameterDescriptor[0]),
                this.sourceProductDescriptors.toArray(new SourceProductDescriptor[0]));
        this.ioParametersPanel = new DefaultIOParametersPanel(getAppContext(), this.operatorDescriptor, getTargetProductSelector(), true);

        this.parameterSupport = new OperatorParameterSupport(this.operatorDescriptor);
        ArrayList<SourceProductSelector> sourceProductSelectorList = this.ioParametersPanel.getSourceProductSelectorList();
        PropertySet propertySet = this.parameterSupport.getPropertySet();
        this.bindingContext = new BindingContext(propertySet);

        SelectionChangeListener currentListenerProduct = new SelectionChangeListener() {
            public void selectionChanged(SelectionChangeEvent event) {
                Product product = sourceProductSelectorList.get(CURRENT_PRODUCT).getSelectedProduct();
                if (product != null) {
                    updateTargetProductName(product);
                }
            }
            public void selectionContextChanged(SelectionChangeEvent event) {
            }
        };

        sourceProductSelectorList.get(CURRENT_PRODUCT).addSelectionChangeListener(currentListenerProduct);

    }

    @Override
    protected void onApply() {
        if (!canApply()) {
            return;
        }

        TargetProductSelectorModel model = targetProductSelector.getModel();
        String productDirPath = model.getProductDir().getAbsolutePath();
        appContext.getPreferences().setPropertyString(SaveProductAsAction.PREFERENCES_KEY_LAST_PRODUCT_DIR, productDirPath);
        try {
            HashMap<String, Product> sourceProducts = ioParametersPanel.createSourceProductsMap();
            Product currentSourceProduct = sourceProducts.get(RECENT_PRODUCT_PROPERTY);
            Product previousSourceProduct = sourceProducts.get(PREVIOUS_PRODUCT_PROPERTY);
            ProductManager productManager = appContext.getProductManager();
            Component parentComponent = getJDialog();
            TargetProductSwingWorker worker = new TargetProductSwingWorker(parentComponent, productManager, model, currentSourceProduct,
                    previousSourceProduct, this.parameterSupport.getParameterMap());
            worker.executeWithBlocking(); // start the thread
        } catch (Throwable t) {
            handleInitialisationError(t);
            return;
        }
    }

    @Override
    protected boolean verifyUserInput(){
        final PropertySet propertySet = bindingContext.getPropertySet();
        ArrayList<SourceProductSelector> sourceProductSelectorList = this.ioParametersPanel.getSourceProductSelectorList();
        Product currentProduct = sourceProductSelectorList.get(CURRENT_PRODUCT).getSelectedProduct();
        Product previousProduct = sourceProductSelectorList.get(PREVIOUS_PRODUCT).getSelectedProduct();
        Object currentProductSourceMask = propertySet.getValue(CURRENT_PRODUCT_SOURCE_MASK);
        Object previousProductSourceMask = propertySet.getValue(PREVIOUS_PRODUCT_SOURCE_MASK);
        String message;
        if ((currentProduct != null) && (previousProduct != null)) {
            if ((ForestCoverChangeOp.isSentinelProduct(currentProduct) && (currentProductSourceMask == null)
                    && (ForestCoverChangeOp.isSentinelProduct(previousProduct))) && (previousProductSourceMask == null)) {

                message = "Products " + currentProduct.getName() + " and " + previousProduct.getName() + " are of type Sentinel 2.\n" +
                        "The forest cover change output product  will take in consideration the cloud masks from these products.";
                showInformationDialog(message);
            } else if ((ForestCoverChangeOp.isSentinelProduct(currentProduct) && (currentProductSourceMask == null)
                    && (ForestCoverChangeOp.isSentinelProduct(previousProduct))) && (previousProductSourceMask != null)) {

                message = "Product " + currentProduct.getName() + " is of type Sentinel 2.\n" +
                        "The forest cover change output product  will take in consideration the cloud masks from this product.";
                showInformationDialog(message);
            } else if ((ForestCoverChangeOp.isSentinelProduct(currentProduct) && (currentProductSourceMask != null)
                    && (ForestCoverChangeOp.isSentinelProduct(previousProduct))) && (previousProductSourceMask == null)) {

                message = "Product " + previousProduct.getName() + " is of type Sentinel 2.\n" +
                        "The forest cover change output product  will take in consideration the cloud masks from this product.";
                showInformationDialog(message);
            }
        }
        String pattern = "[0-9]+([ ]*,[ ]*[0-9]*)*";
        Object landCoverIndicesProperty = propertySet.getValue(LAND_COVER_MAP_INDICES_PROPERTY);
        if (propertySet.getValue(LAND_COVER_EXTERNAL_FILE_PROPERTY) != null && landCoverIndicesProperty == null) {
            showErrorDialog("No land cover map forest indices specified.");
            return false;
        }
        if (landCoverIndicesProperty != null){
            String indices = landCoverIndicesProperty.toString();
            if(!indices.matches(pattern)) {
                showErrorDialog("Invalid land cover map forest indices.");
                return false;
            }
        }
        return true;
    }

    @Override
    public int show() {
        this.ioParametersPanel.initSourceProductSelectors();
        if (this.form == null) {
            initForm();
            if (getJDialog().getJMenuBar() == null) {
                OperatorMenu operatorMenu = createDefaultMenuBar();
                getJDialog().setJMenuBar(operatorMenu.createDefaultMenu());
            }
        }

        setContent(this.form);
        return super.show();
    }

    @Override
    public void hide() {
        ioParametersPanel.releaseSourceProductSelectors();
        super.hide();
    }

    @Override
    protected Product createTargetProduct() throws Exception {
        HashMap<String, Product> sourceProducts = this.ioParametersPanel.createSourceProductsMap();
        return GPF.createProduct(this.operatorName, this.parameterSupport.getParameterMap(), sourceProducts);
    }

    void setTargetProductNameSuffix(String suffix) {
        this.targetProductNameSuffix = suffix;
    }

    private void updateTargetProductName(Product product) {
        String productName = "";
        if (product != null) {
            productName = product.getName();
        }
        final TargetProductSelectorModel targetProductSelectorModel = getTargetProductSelector().getModel();
        targetProductSelectorModel.setProductName(productName + getTargetProductNameSuffix());
    }

    private  String getTargetProductNameSuffix() {
        return targetProductNameSuffix;
    }

    private void initForm() {
        this.form = new JTabbedPane();
        this.form.add("I/O Parameters", this.ioParametersPanel);
        final TableLayout layout = new TableLayout(1);
        layout.setTableAnchor(TableLayout.Anchor.WEST);
        layout.setTableFill(TableLayout.Fill.BOTH);
        layout.setTableWeightX(1.0);
        layout.setTableWeightY(0.0);
        layout.setRowWeightY(2, 1.0);
        layout.setTablePadding(3, 3);
        ArrayList<SourceProductSelector> sourceProductSelectorList = this.ioParametersPanel.getSourceProductSelectorList();
        if (this.bindingContext.getPropertySet().getProperties().length > 0) {
            PropertyContainer container = new PropertyContainer();
            container.addProperties(this.bindingContext.getPropertySet().getProperties());
            if (this.parameterGroupDescriptors != null) {
                for (Map.Entry<String, List<String>> pair : this.parameterGroupDescriptors.entrySet()) {
                    for (String prop : pair.getValue()) {
                        container.removeProperty(this.bindingContext.getPropertySet().getProperty(prop));
                    }
                }
            }
            final PropertyPane parametersPane = new PropertyPane(container);
            final JPanel parametersPanel = new JPanel(layout);
            parametersPanel.add(parametersPane.createPanel());
            parametersPanel.setBorder(new EmptyBorder(4, 4, 4, 4));
            form.add("Processing Parameters", new JScrollPane(parametersPanel));
            if (this.parameterGroupDescriptors != null) {
                for (Map.Entry<String, List<String>> pair : this.parameterGroupDescriptors.entrySet()) {
                        parametersPanel.add(createPanel(pair.getKey() + " parameters", this.bindingContext, pair.getValue()));

                }
            }
        }
    }



    private void showSaveInfo(long saveTime) {
        File productFile = getTargetProductSelector().getModel().getProductFile();
        String message = MessageFormat.format(
                "<html>The target product has been successfully written to<br>{0}<br>" +
                        "Total time spend for processing: {1}",
                formatFile(productFile),
                formatDuration(saveTime)
        );
        showSuppressibleInformationDialog(message, "saveInfo");
    }

    private String formatFile(File file) {
        return FileUtils.getDisplayText(file, 54);
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        millis -= seconds * 1000;
        long minutes = seconds / 60;
        seconds -= minutes * 60;
        long hours = minutes / 60;
        minutes -= hours * 60;
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }

    private void showSaveAndOpenInAppInfo(long saveTime) {
        File productFile = getTargetProductSelector().getModel().getProductFile();
        String message = MessageFormat.format(
                "<html>The target product has been successfully written to<br>" +
                        "<p>{0}</p><br>" +
                        "and has been opened in {1}.<br><br>" +
                        "Total time spend for processing: {2}<br>",
                formatFile(productFile),
                appContext.getApplicationName(),
                formatDuration(saveTime)
        );
        showSuppressibleInformationDialog(message, "saveAndOpenInAppInfo");
    }

    private JPanel createPanel(String name, BindingContext bindingContext, List<String> parameters) {
        PropertyContainer container = new PropertyContainer();
        for (String parameter: parameters) {
            Property prop = bindingContext.getPropertySet().getProperty(parameter);
            container.addProperty(prop);
        }

        final JPanel panel = createPanel(container);
        panel.setBorder(BorderFactory.createTitledBorder(name));
        return panel;

//        final PropertyPane parametersPane = new PropertyPane(container);
//        final JPanel panel = parametersPane.createPanel();
//        panel.setBorder(BorderFactory.createTitledBorder(name));
//        return panel;
    }

    private void processAnnotationsRec(Class<?> operatorClass) {
        Class<?> superclass = operatorClass.getSuperclass();
        if (superclass != null && !superclass.equals(Operator.class)) {
            processAnnotationsRec(superclass);
        }

        final Field[] declaredFields = operatorClass.getDeclaredFields();
        for (Field declaredField : declaredFields) {

            String fieldName = declaredField.getName();
            Class<?> fieldType = declaredField.getType();

            ParameterGroup parameterGroupAnnotation = declaredField.getAnnotation(ParameterGroup.class);
            if(parameterGroupAnnotation!=null){
                String alias = parameterGroupAnnotation.alias();
                if (parameterGroupDescriptors == null) {
                    parameterGroupDescriptors = new HashMap<>();
                }
                List<String>value = parameterGroupDescriptors.get(alias);
                if(value == null){
                    value = new ArrayList<>();
                    parameterGroupDescriptors.put(alias, value);
                }
                value.add(fieldName);
            }
            Parameter parameterAnnotation = declaredField.getAnnotation(Parameter.class);
            if (parameterAnnotation != null) {
                if (parameterDescriptors == null) {
                    parameterDescriptors = new ArrayList<>();
                }
                boolean isDeprecated = declaredField.getAnnotation(Deprecated.class) != null;
                parameterDescriptors.add(new AnnotationParameterDescriptor(fieldName, fieldType, isDeprecated, parameterAnnotation));
                continue;
            }

            SourceProduct sourceProductAnnotation = declaredField.getAnnotation(SourceProduct.class);
            if (sourceProductAnnotation != null && Product.class.isAssignableFrom(fieldType)) {
                if (sourceProductDescriptors == null) {
                    sourceProductDescriptors = new ArrayList<>();
                }
                sourceProductDescriptors.add(new AnnotationSourceProductDescriptor(fieldName, sourceProductAnnotation));
                continue;
            }

            SourceProducts sourceProductsAnnotation = declaredField.getAnnotation(SourceProducts.class);
            if (sourceProductsAnnotation != null && Product[].class.isAssignableFrom(fieldType)) {
                continue;
            }

            TargetProduct targetProductAnnotation = declaredField.getAnnotation(TargetProduct.class);
            if (targetProductAnnotation != null) {
                continue;
            }

            TargetProperty targetPropertyAnnotation = declaredField.getAnnotation(TargetProperty.class);
            if (targetPropertyAnnotation != null) {
                if (targetPropertyDescriptors == null) {
                    targetPropertyDescriptors = new ArrayList<>();
                }
                targetPropertyDescriptors.add(new AnnotationTargetPropertyDescriptor(fieldName, fieldType, targetPropertyAnnotation));
            }
        }
    }

    private OperatorMenu createDefaultMenuBar() {
        return new OperatorMenu(getJDialog(), this.operatorDescriptor, this.parameterSupport, getAppContext(), getHelpID());
    }

    private static JPanel createPanel(PropertySet propertyContainer) {
        Property[] properties = propertyContainer.getProperties();
        BindingContext bindingContext = new BindingContext((propertyContainer));

        boolean displayUnitColumn = wantDisplayUnitColumn(properties);
        TableLayout layout = new TableLayout(displayUnitColumn ? 3 : 2);
        layout.setTableAnchor(TableLayout.Anchor.WEST);
        layout.setTableFill(TableLayout.Fill.HORIZONTAL);
        layout.setTablePadding(3, 3);
        final JPanel panel = new JPanel(layout);

        int rowIndex = 0;
        final PropertyEditorRegistry registry = PropertyEditorRegistry.getInstance();
        for (Property property : properties) {
            PropertyDescriptor descriptor = property.getDescriptor();
            if (isInvisible(descriptor)) {
                continue;
            }
            PropertyEditor propertyEditor = registry.findPropertyEditor(descriptor);
            JComponent[] components = propertyEditor.createComponents(descriptor, bindingContext);
            if (components.length == 2) {
                layout.setCellWeightX(rowIndex, 0, 0.0);
                panel.add(components[1], cell(rowIndex, 0));
                layout.setCellWeightX(rowIndex, 1, 1.0);
                if(components[0] instanceof JScrollPane) {
                    layout.setRowWeightY(rowIndex, 1.0);
                    layout.setRowFill(rowIndex, TableLayout.Fill.BOTH);
                }
                panel.add(components[0], cell(rowIndex, 1));
            } else {
                layout.setCellColspan(rowIndex, 0, 2);
                layout.setCellWeightX(rowIndex, 0, 1.0);
                panel.add(components[0], cell(rowIndex, 0));
            }
            if (displayUnitColumn) {
                final JLabel label = new JLabel("");
                if (descriptor.getUnit() != null) {
                    label.setText(descriptor.getUnit());
                }
                layout.setCellWeightX(rowIndex, 2, 0.0);
                panel.add(label, cell(rowIndex, 2));
            }
            rowIndex++;
        }
        layout.setCellColspan(rowIndex, 0, 2);
        layout.setCellWeightX(rowIndex, 0, 1.0);
        layout.setCellWeightY(rowIndex, 0, 0.5);
        return panel;
    }

    private static boolean isInvisible(PropertyDescriptor descriptor) {
        return Boolean.FALSE.equals(descriptor.getAttribute("visible")) || descriptor.isDeprecated();
    }

    private static boolean wantDisplayUnitColumn(Property[] models) {
        boolean showUnitColumn = false;
        for (Property model : models) {
            PropertyDescriptor descriptor = model.getDescriptor();
            if (isInvisible(descriptor)) {
                continue;
            }
            String unit = descriptor.getUnit();
            if (!(unit == null || unit.length() == 0)) {
                showUnitColumn = true;
                break;
            }
        }
        return showUnitColumn;
    }

    private class TargetProductSwingWorker extends ProgressMonitorSwingWorker<Product, Object> {
        private final ProductManager productManager;
        private final TargetProductSelectorModel model;
        private final Product currentSourceProduct;
        private final Product previousSourceProduct;
        private final Map<String, Object> parameters;

        private long totalTime;

        private TargetProductSwingWorker(Component parentComponent, ProductManager productManager, TargetProductSelectorModel model, Product currentSourceProduct,
                                         Product previousSourceProduct, Map<String, Object> parameters) {

            super(parentComponent, "Run Forest Cover Change");

            this.productManager = productManager;
            this.model = model;
            this.currentSourceProduct = currentSourceProduct;
            this.previousSourceProduct = previousSourceProduct;
            this.parameters = parameters;
            this.totalTime = 0L;
        }

        @Override
        protected Product doInBackground(ProgressMonitor pm) throws Exception {
            pm.beginTask("Running...", this.model.isOpenInAppSelected() ? 100 : 95);

            Product productToReturn = null;
            Product operatorTargetProduct = null;
            try {
                long startTime = System.currentTimeMillis();

                Map<String, Product> sourceProducts = new HashMap<String, Product>();
                sourceProducts.put(RECENT_PRODUCT_PROPERTY, this.currentSourceProduct);
                sourceProducts.put(PREVIOUS_PRODUCT_PROPERTY, this.previousSourceProduct);

                // create the operator
                Operator operator = GPF.getDefaultInstance().createOperator("ForestCoverChangeOp", this.parameters, sourceProducts, null);

                // execute the operator
                operator.execute(ProgressMonitor.NULL);

                // get the operator target product
                operatorTargetProduct = operator.getTargetProduct();

                productToReturn = operatorTargetProduct;

                if (this.model.isSaveToFileSelected()) {
                    File targetFile = this.model.getProductFile();
                    String formatName = this.model.getFormatName();
                    GPF.writeProduct(operatorTargetProduct, targetFile, formatName, false, false, ProgressMonitor.NULL);

                    productToReturn = ProductIO.readProduct(targetFile);

                    operatorTargetProduct.dispose();
                }

                this.totalTime = System.currentTimeMillis() - startTime;
            } finally {
                pm.done();
                Preferences preferences = SnapApp.getDefault().getPreferences();
                if (preferences.getBoolean(GPF.BEEP_AFTER_PROCESSING_PROPERTY, false)) {
                    Toolkit.getDefaultToolkit().beep();
                }
            }
            return productToReturn;
        }

        @Override
        protected void done() {
            try {
                final Product targetProduct = get();
                if (this.model.isSaveToFileSelected() && this.model.isOpenInAppSelected()) {
                    this.productManager.addProduct(targetProduct);
                    showSaveAndOpenInAppInfo(this.totalTime);
                } else if (this.model.isOpenInAppSelected()) {
                    this.productManager.addProduct(targetProduct);
                    showOpenInAppInfo();
                } else {
                    showSaveInfo(this.totalTime);
                }
            } catch (InterruptedException e) {
                // ignore
            } catch (ExecutionException e) {
                handleProcessingError(e.getCause());
            } catch (Throwable t) {
                handleProcessingError(t);
            }
        }
    }
}