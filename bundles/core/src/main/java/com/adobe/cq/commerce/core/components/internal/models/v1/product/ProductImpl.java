/*******************************************************************************
 *
 *    Copyright 2019 Adobe. All rights reserved.
 *    This file is licensed to you under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License. You may obtain a copy
 *    of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software distributed under
 *    the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 *    OF ANY KIND, either express or implied. See the License for the specific language
 *    governing permissions and limitations under the License.
 *
 ******************************************************************************/

package com.adobe.cq.commerce.core.components.internal.models.v1.product;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import javax.inject.Inject;

import com.adobe.cq.commerce.graphql.client.GraphqlClient;
import com.adobe.cq.commerce.graphql.client.GraphqlRequest;
import com.adobe.cq.commerce.graphql.client.GraphqlResponse;
import com.adobe.cq.commerce.magento.graphql.ComplexTextValue;
import com.adobe.cq.commerce.magento.graphql.Query;
import com.adobe.cq.commerce.magento.graphql.gson.Error;
import com.adobe.cq.commerce.magento.graphql.gson.QueryDeserializer;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.Self;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.cq.commerce.core.components.internal.models.v1.Utils;
import com.adobe.cq.commerce.core.components.models.product.Asset;
import com.adobe.cq.commerce.core.components.models.product.Product;
import com.adobe.cq.commerce.core.components.models.product.Variant;
import com.adobe.cq.commerce.core.components.models.product.VariantAttribute;
import com.adobe.cq.commerce.core.components.models.product.VariantValue;
import com.adobe.cq.commerce.magento.graphql.ConfigurableAttributeOption;
import com.adobe.cq.commerce.magento.graphql.ConfigurableProduct;
import com.adobe.cq.commerce.magento.graphql.ConfigurableProductOptions;
import com.adobe.cq.commerce.magento.graphql.ConfigurableProductOptionsValues;
import com.adobe.cq.commerce.magento.graphql.ConfigurableVariant;
import com.adobe.cq.commerce.magento.graphql.FilterTypeInput;
import com.adobe.cq.commerce.magento.graphql.MediaGalleryEntry;
import com.adobe.cq.commerce.magento.graphql.Operations;
import com.adobe.cq.commerce.magento.graphql.ProductFilterInput;
import com.adobe.cq.commerce.magento.graphql.ProductInterface;
import com.adobe.cq.commerce.magento.graphql.ProductInterfaceQueryDefinition;
import com.adobe.cq.commerce.magento.graphql.ProductPricesQueryDefinition;
import com.adobe.cq.commerce.magento.graphql.ProductsQueryDefinition;
import com.adobe.cq.commerce.magento.graphql.QueryQuery.ProductsArgumentsDefinition;
import com.adobe.cq.commerce.magento.graphql.SimpleProduct;
import com.adobe.cq.commerce.magento.graphql.SimpleProductQueryDefinition;
import com.adobe.cq.commerce.magento.graphql.StoreConfigQueryDefinition;
import com.day.cq.wcm.api.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Model(adaptables = SlingHttpServletRequest.class, adapters = Product.class, resourceType = ProductImpl.RESOURCE_TYPE)
public class ProductImpl implements Product {

    protected static final String RESOURCE_TYPE = "venia/components/commerce/product/v1/product";
    private static final Logger LOG = LoggerFactory.getLogger(ProductImpl.class);
    private static final String PRODUCT_IMAGE_FOLDER = "catalog/product";

    @Self
    private SlingHttpServletRequest request;

    @Inject
    private Resource resource;

    @Inject
    private Page currentPage;

    private ProductInterface product;

    private String mediaBaseUrl;

    private NumberFormat priceFormatter;

    private Boolean configurable;

    @PostConstruct
    private void initModel() {
        // Parse slug from URL
        String slug = this.parseProductSlug();

        // Get GraphqlClient from the page resource. This will look for a
        // cq:catalogIdentifier property on the page or any ancestor and a
        // corresponding OSGi configuration.
        GraphqlClient client = resource.adaptTo(GraphqlClient.class);
        if (client == null) {
            LOG.error("Cannot get a GraphqlClient using the resource at {}", resource.getPath());
        }

        this.product = this.getProduct(client, slug);

        // Initialize NumberFormatter with locale from current page.
        // Alternatively, the locale can potentially be retrieved via
        // the storeConfig query introduced with Magento 2.3.1
        Locale locale = currentPage.getLanguage(false);
        this.priceFormatter = Utils.buildPriceFormatter(locale, this.getCurrency());
    }

    @Override
    public Boolean getFound() {
        return this.product != null;
    }

    @Override
    public String getName() {
        return this.product.getName();
    }

    @Override
    public String getDescription() {
        return this.safeDescription(this.product);
    }

    @Override
    public String getSku() {
        return this.product.getSku();
    }

    @Override
    public String getCurrency() {
        return this.product.getPrice().getRegularPrice().getAmount().getCurrency().toString();
    }

    @Override
    public Double getPrice() {
        return this.product.getPrice().getRegularPrice().getAmount().getValue();
    }

    @Override
    public Boolean getInStock() {
        return this.product.getStockStatus().name().equals("IN_STOCK");
    }

    @Override
    public Boolean isConfigurable() {
        if (this.configurable == null) {
            this.configurable = this.product instanceof ConfigurableProduct;
        }

        return this.configurable;
    }

    @Override
    public String getVariantsJson() {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(this.getVariants());
        } catch (JsonProcessingException e) {
            LOG.warn("Could not serialize product variants");
            return "[]";
        }
    }

    @Override
    public List<Variant> getVariants() {
        // Don't return any variants if the current product
        // is not of type ConfigurableProduct.
        if (!this.isConfigurable()) {
            return Collections.emptyList();
        }
        ConfigurableProduct product = (ConfigurableProduct) this.product;

        return product.getVariants().parallelStream().map(this::mapVariant).collect(Collectors.toList());
    }

    @Override
    public List<Asset> getAssets() {
        return this.filterAndSortAssets(this.product.getMediaGalleryEntries());
    }

    public String getAssetsJson() {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(getAssets());
        } catch (JsonProcessingException e) {
            LOG.error(e.getMessage(), e);
            return "";
        }
    }

    @Override
    public List<VariantAttribute> getVariantAttributes() {
        // Don't return any variant selection properties if the current
        // product is not of type ConfigurableProduct.
        if (!this.isConfigurable()) {
            return Collections.emptyList();
        }

        ConfigurableProduct product = (ConfigurableProduct) this.product;

        List<VariantAttribute> optionList = new ArrayList<>();
        for (ConfigurableProductOptions option : product.getConfigurableOptions()) {
            optionList.add(this.mapVariantAttribute(option));
        }

        return optionList;
    }

    @Override
    public String getFormattedPrice() {
        return this.priceFormatter.format(this.getPrice());
    }

    /* --- GraphQL queries --- */

    public ProductPricesQueryDefinition generatePriceQuery() {
        return q -> q
            .regularPrice(rp -> rp
                .amount(a -> a
                    .currency()
                    .value()));
    }

    public SimpleProductQueryDefinition generateSimpleProductQuery() {
        return q -> q
            .id()
            .sku()
            .name()
            .description(d -> d.html())
            .image(i -> i.label().url())
            .thumbnail(t -> t.label().url())
            .urlKey()
            .stockStatus()
            .color()
            .price(this.generatePriceQuery())
            .mediaGalleryEntries(g -> g
                .disabled()
                .file()
                .label()
                .position()
                .mediaType());
    }

    public ProductInterfaceQueryDefinition generateProductQuery() {
        // Custom attributes or attributes that are part of a non-standard
        // attribute set have to be added to the query manually. This also
        // requires the customer to use newly generated GraphQL classes.
        return q -> q
            .id()
            .sku()
            .name()
            .image(i -> i.label().url())
            .thumbnail(t -> t.label().url())
            .urlKey()
            .stockStatus()
            .price(this.generatePriceQuery())
            .categories(c -> c.urlPath())
            .mediaGalleryEntries(g -> g
                .disabled()
                .file()
                .label()
                .position()
                .mediaType())
            .onConfigurableProduct(cp -> cp
                .configurableOptions(o -> o
                    .label()
                    .attributeCode()
                    .values(v -> v
                        .valueIndex()
                        .label()))
                .variants(v -> v
                    .attributes(a -> a
                        .code()
                        .valueIndex()
                    )
                    .product(this.generateSimpleProductQuery())));
    }

    private StoreConfigQueryDefinition generateStoreConfigQuery() {
        return q -> q.secureBaseMediaUrl();
    }

    /* --- Mapping methods --- */

    private Variant mapVariant(ConfigurableVariant variant) {
        SimpleProduct product = variant.getProduct();

        VariantImpl productVariant = new VariantImpl();
        productVariant.setName(product.getName());
        productVariant.setDescription(this.safeDescription(product));
        productVariant.setSku(product.getSku());
        productVariant.setColor(product.getColor());
        productVariant.setCurrency(product.getPrice().getRegularPrice().getAmount().getCurrency().toString());
        productVariant.setPrice(product.getPrice().getRegularPrice().getAmount().getValue());
        productVariant.setFormattedPrice(this.priceFormatter.format(productVariant.getPrice()));
        productVariant.setInStock(product.getStockStatus().name().equals("IN_STOCK"));

        // Map variant attributes
        for (ConfigurableAttributeOption option : variant.getAttributes()) {
            productVariant.getVariantAttributes().put(option.getCode(), option.getValueIndex());
        }

        List<Asset> assets = this.filterAndSortAssets(product.getMediaGalleryEntries());
        productVariant.setAssets(assets);

        return productVariant;
    }

    private List<Asset> filterAndSortAssets(List<MediaGalleryEntry> assets) {
        return assets.parallelStream()
            .filter(e -> !e.getDisabled() && e.getMediaType().equals("image"))
            .map(this::mapAsset)
            .sorted(Comparator.comparing(Asset::getPosition))
            .collect(Collectors.toList());
    }

    private Asset mapAsset(MediaGalleryEntry entry) {
        AssetImpl asset = new AssetImpl();
        asset.setLabel(entry.getLabel());
        asset.setPosition(entry.getPosition());
        asset.setType(entry.getMediaType());

        // TODO WORKAROUND
        // Magento media gallery only provides that file path but not a full image url yet, we need the mediaBaseUrl
        // from the storeConfig to construct the full image url
        asset.setPath(mediaBaseUrl + PRODUCT_IMAGE_FOLDER + entry.getFile());

        return asset;
    }

    private VariantValue mapVariantValue(ConfigurableProductOptionsValues value) {
        VariantValueImpl variantValue = new VariantValueImpl();
        variantValue.setId(value.getValueIndex());
        variantValue.setLabel(value.getLabel());

        return variantValue;
    }

    private VariantAttribute mapVariantAttribute(ConfigurableProductOptions option) {
        // Get list of values
        List<VariantValue> values = option.getValues().parallelStream().map(this::mapVariantValue).collect(Collectors.toList());

        // Create attribute map
        VariantAttributeImpl attribute = new VariantAttributeImpl();
        attribute.setLabel(option.getLabel());
        attribute.setId(option.getAttributeCode());
        attribute.setValues(values);

        return attribute;
    }

    /* --- Utility methods --- */

    /**
     * Returns the selector of the current request which is expected to be the
     * product slug.
     *
     * @return product slug
     */
    private String parseProductSlug() {
        return this.request.getRequestPathInfo().getSelectorString();
    }

    private ProductInterface getProduct(GraphqlClient client, String slug) {
        // Search parameters
        FilterTypeInput input = new FilterTypeInput().setEq(slug);
        ProductFilterInput filter = new ProductFilterInput().setUrlKey(input);
        ProductsArgumentsDefinition searchArgs = s -> s.filter(filter);

        // GraphQL query
        ProductsQueryDefinition queryArgs = q -> q.items(this.generateProductQuery());
        String queryString = Operations.query(query -> query
            .products(searchArgs, queryArgs)
            .storeConfig(this.generateStoreConfigQuery())).toString();

        // Send GraphQL request
        GraphqlResponse<Query, Error> response = client.execute(new GraphqlRequest(queryString), Query.class, Error.class, QueryDeserializer.getGson());

        // Get product list from response
        Query rootQuery = response.getData();
        List<ProductInterface> products = rootQuery.getProducts().getItems();

        // TODO WORKAROUND
        // we need a temporary detour and use storeconfig to get the base media url since the product media gallery only returns the images file names but no full URLs
        mediaBaseUrl = rootQuery.getStoreConfig().getSecureBaseMediaUrl();

        // Return first product in list
        if (products.size() > 0) {
            return products.get(0);
        }

        return null;
    }

    private String safeDescription(ProductInterface product) {
        ComplexTextValue description = product.getDescription();
        if (description == null) {
            return null;
        }
        return description.getHtml();
    }
}