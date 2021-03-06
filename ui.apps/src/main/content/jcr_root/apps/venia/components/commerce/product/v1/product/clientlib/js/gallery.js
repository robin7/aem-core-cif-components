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

(function () {

    //TODO make this global so that other components would use it
    const events = {
        variantChanged: "variantchanged"
    };

    class Gallery {
        _galleryItems = [];
        _currentItem = '';
        _currentItemIndex = 0;
        _rootNode = '';

        static selectors = {
            galleryRoot: "div[data-gallery-role='galleryroot']",
            currentImageContainer: "img[data-gallery-role='currentimage']",
            galleryThumbnail: "button[data-gallery-role='galleryitem']",
            leftArrow: "button[data-gallery-role='moveleft']",
            rightArrow: "button[data-gallery-role='moveright']"
        };

        constructor(props) {
            this._galleryItems = props.galleryItems;
            if (this._galleryItems.length > 0) {
                this._rootNode = document.querySelector(Gallery.selectors.galleryRoot);
                this._currentItem = this._galleryItems[this._currentItemIndex];
                const firstThumbail = this._rootNode.querySelector("button.thumbnail:first-of-type");
                if (firstThumbail) {
                    firstThumbail.classList.add("thumbnail__rootSelected");
                }
                this._installEvents();
            }
            document.addEventListener(events.variantChanged, (event) => {
                if (!event.detail.variant || !event.detail.variant.assets) {
                    return;
                }

                if (!this._rootNode) {
                    // if the new variant doesn't have any assets the root node is not available
                    // so it makes no sense to update the gallery
                    return;
                }
                this.updateGalleryItems({galleryItems: event.detail.variant.assets});
            });
        }

        /**
         * Updates the list of items from this gallery. An item from this has three mandatory properties:
         * position - the position in the list of assets
         * path - the url of the image
         * label - the label of the asset
         *
         * @param galleryItems
         */
        updateGalleryItems = ({galleryItems}) => {
            if (!galleryItems || galleryItems.length === 0) {
                //don't do anything if we don't get items
                return;
            }
            this._galleryItems = galleryItems;
            this._currentItemIndex = 1;
            this._currentItem = galleryItems[this._currentItemIndex];
            this._recreateDomThumbnails();

        };

        /*
         Recreates the DOM nodes for the thumbnails
         */
        _recreateDomThumbnails = () => {

            const thumbnailList = this._rootNode.querySelector("div.thumbnailList__root");

            /* Creates an thumbnail DOM string from an item data  */
            const createItemDomString = ({path, label}, index) => (
                `<button class="thumbnail thumbnail__root" data-gallery-role="galleryitem" data-gallery-index="${index}">\n\t<img class="thumbnail__image" src="${path}" alt="${label}"/>\n</button>`
            );

            /* Transforms a DOM string into an actual DOM Node object */
            const toElement = (domString) => {
                const html = new DOMParser().parseFromString(domString, "text/html");
                return html.body.firstChild;
            };

            /* Empty the list of thumbnails first */
            while (thumbnailList.firstChild) {
                thumbnailList.removeChild(thumbnailList.firstChild);
            }

            /* Append the Node objects to the list */
            this._galleryItems.forEach((item, index) => {
                thumbnailList.appendChild(toElement(createItemDomString(item, index)));
            });

            /* Preselect the first element */
            thumbnailList.firstChild.classList.add("thumbnail__rootSelected");

            /* Re-install the events since the listeners are lost when we remove the nodes */
            this._installThumbnailEvents();
        };

        /**
         * Switches the current image displayed by the gallery to the one at the supplied position.
         * @private
         */
        _switchCurrentImage(index) {
            // replace the current image
            const currentImageNode = this._rootNode.querySelector(Gallery.selectors.currentImageContainer);
            let galleryItem = this._galleryItems[index];
            currentImageNode.src = galleryItem.path;

            // updated the internal state
            this._currentItem = galleryItem;
            this._currentItemIndex = index;

            // update the style of the selected / unselected thumbnails
            const currentlySelectedThumb = this._rootNode.querySelector("button.thumbnail__rootSelected");
            if (currentlySelectedThumb) {
                currentlySelectedThumb.classList.remove("thumbnail__rootSelected");
            }
            const currentThumb = this._rootNode.querySelector(`button[data-gallery-index='${index}'`);
            currentThumb.classList.add("thumbnail__rootSelected");
        }


        /**
         * Adds the listeners to the thumbnails
         * @private
         */
        _installThumbnailEvents() {

            const handleThumbnailClick = (idx, event) => {
                let currentTarget = event.currentTarget;
                let src = currentTarget.firstElementChild.src;
                this._switchCurrentImage(idx);


                const thumb = event.currentTarget;
            };

            document.querySelectorAll(Gallery.selectors.galleryThumbnail).forEach((node, idx) => {

                node.addEventListener('click', (event) => {
                    event.preventDefault();
                    handleThumbnailClick(idx, event);
                })

            });
        }

        /**
         * Installs events listeners
         * @private
         */
        _installEvents() {

            this._installThumbnailEvents()
            document.querySelector(Gallery.selectors.leftArrow).addEventListener('click', this._handleArrowClick);
            document.querySelector(Gallery.selectors.rightArrow).addEventListener('click', this._handleArrowClick);
        }

        /**
         * Handles the click on the "chevrons" for gallery navigation
         * @param event
         * @private
         */
        _handleArrowClick = (event) => {
            event.preventDefault();
            const direction = event.currentTarget.dataset["galleryRole"];

            if (direction === 'moveleft') {

                if (this._currentItemIndex <= 0) {
                    this._currentItemIndex = this._galleryItems.length - 1;
                } else {
                    this._currentItemIndex--
                }

            } else if (direction === 'moveright') {
                if (this._currentItemIndex >= this._galleryItems.length - 1) {
                    this._currentItemIndex = 0;
                } else {
                    this._currentItemIndex++;
                }
            }

            this._switchCurrentImage(this._currentItemIndex);

        };
    }

    function onDocumentReady() {
        const galleryRoot = document.querySelector(Gallery.selectors.galleryRoot);
        let galleryItemsJson = galleryRoot ? galleryRoot.dataset.galleryItems : {"assets": []};

        const galleryItems = JSON.parse(galleryItemsJson);
        const gallery = new Gallery({galleryItems});
    }

    if (document.readyState !== "loading") {
        onDocumentReady()
    } else {
        document.addEventListener("DOMContentLoaded", onDocumentReady);
    }
})();