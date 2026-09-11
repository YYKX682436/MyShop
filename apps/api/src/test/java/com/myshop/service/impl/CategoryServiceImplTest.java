package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.entity.ProductCategory;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.CategoryUpsertRequest;
import com.myshop.model.response.CategoryResponse;
import com.myshop.repository.ProductCategoryRepository;
import com.myshop.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock
    private ProductCategoryRepository categoryRepository;

    @Mock
    private ProductRepository productRepository;

    @Test
    void listReturnsRootsWithStableOrderedChildren() {
        ProductCategory rootB = category(UUID.randomUUID(), "Root B", null, 2);
        ProductCategory rootA = category(UUID.randomUUID(), "Root A", null, 1);
        ProductCategory childA2 = category(UUID.randomUUID(), "Child A2", rootA.getId(), 2);
        ProductCategory childA1 = category(UUID.randomUUID(), "Child A1", rootA.getId(), 1);
        when(categoryRepository.findByIsDeletedOrderBySortOrderAscIdAsc(0))
                .thenReturn(List.of(childA2, rootB, childA1, rootA));

        List<CategoryResponse> result = service().listCategories();

        assertIterableEquals(List.of("Root A", "Root B"), result.stream().map(CategoryResponse::name).toList());
        assertIterableEquals(List.of("Child A1", "Child A2"),
                result.get(0).children().stream().map(CategoryResponse::name).toList());
        assertEquals(rootA.getId(), result.get(0).children().get(0).parentId());
        assertNull(result.get(0).parentId());
    }

    @Test
    void createCreatesATrimmedRootWithNoParent() {
        when(categoryRepository.existsByNameAndParentIdIsNullAndIsDeleted("New root", 0)).thenReturn(false);

        service().createCategory(new CategoryUpsertRequest("  New root  ", null, null));

        ArgumentCaptor<ProductCategory> captor = ArgumentCaptor.forClass(ProductCategory.class);
        verify(categoryRepository).save(captor.capture());
        assertEquals("New root", captor.getValue().getName());
        assertNull(captor.getValue().getParentId());
        assertEquals(0, captor.getValue().getSortOrder());
    }

    @Test
    void createTrimsNamesAndCreatesAChildOnlyUnderAnActiveRoot() {
        UUID rootId = UUID.randomUUID();
        ProductCategory root = category(rootId, "Root", null, 0);
        when(categoryRepository.findById(rootId)).thenReturn(Optional.of(root));
        when(categoryRepository.existsByNameAndParentIdAndIsDeleted("New child", rootId, 0))
                .thenReturn(false);

        service().createCategory(new CategoryUpsertRequest("  New child  ", 3, rootId));

        ArgumentCaptor<ProductCategory> captor = ArgumentCaptor.forClass(ProductCategory.class);
        verify(categoryRepository).save(captor.capture());
        assertEquals("New child", captor.getValue().getName());
        assertEquals(rootId, captor.getValue().getParentId());
        assertEquals(3, captor.getValue().getSortOrder());
    }

    @Test
    void createRejectsMissingDeletedOrSecondLevelParents() {
        UUID parentId = UUID.randomUUID();
        when(categoryRepository.findById(parentId)).thenReturn(Optional.empty());
        BusinessException missing = assertThrows(BusinessException.class,
                () -> service().createCategory(new CategoryUpsertRequest("Child", null, parentId)));
        assertEquals(ErrorCode.CATEGORY_PARENT_INVALID, missing.getCode());

        ProductCategory deleted = category(parentId, "Deleted", null, 0);
        deleted.setIsDeleted(1);
        when(categoryRepository.findById(parentId)).thenReturn(Optional.of(deleted));
        BusinessException deletedFailure = assertThrows(BusinessException.class,
                () -> service().createCategory(new CategoryUpsertRequest("Child", null, parentId)));
        assertEquals(ErrorCode.CATEGORY_PARENT_INVALID, deletedFailure.getCode());

        ProductCategory child = category(parentId, "Already child", UUID.randomUUID(), 0);
        when(categoryRepository.findById(parentId)).thenReturn(Optional.of(child));
        BusinessException thirdLevel = assertThrows(BusinessException.class,
                () -> service().createCategory(new CategoryUpsertRequest("Grandchild", null, parentId)));
        assertEquals(ErrorCode.CATEGORY_PARENT_INVALID, thirdLevel.getCode());
        verify(categoryRepository, never()).save(any(ProductCategory.class));
    }

    @Test
    void siblingNamesAreUniqueButTheSameNameIsAllowedUnderDifferentRoots() {
        UUID rootId = UUID.randomUUID();
        ProductCategory root = category(rootId, "Root", null, 0);
        when(categoryRepository.findById(rootId)).thenReturn(Optional.of(root));
        when(categoryRepository.existsByNameAndParentIdAndIsDeleted("Same", rootId, 0)).thenReturn(true);

        BusinessException duplicateChild = assertThrows(BusinessException.class,
                () -> service().createCategory(new CategoryUpsertRequest("Same", null, rootId)));
        assertEquals(ErrorCode.CATEGORY_NAME_EXISTS, duplicateChild.getCode());

        UUID otherRootId = UUID.randomUUID();
        ProductCategory otherRoot = category(otherRootId, "Other root", null, 0);
        when(categoryRepository.findById(otherRootId)).thenReturn(Optional.of(otherRoot));
        when(categoryRepository.existsByNameAndParentIdAndIsDeleted("Same", otherRootId, 0)).thenReturn(false);
        service().createCategory(new CategoryUpsertRequest("Same", null, otherRootId));
        verify(categoryRepository).save(any(ProductCategory.class));
    }

    @Test
    void rootWithChildrenCannotBeMovedUnderAnotherRoot() {
        UUID rootId = UUID.randomUUID();
        UUID otherRootId = UUID.randomUUID();
        when(categoryRepository.findById(rootId)).thenReturn(Optional.of(category(rootId, "Root", null, 0)));
        when(categoryRepository.countByParentIdAndIsDeleted(rootId, 0)).thenReturn(1L);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service().updateCategory(rootId,
                        new CategoryUpsertRequest("Root", null, otherRootId)));

        assertEquals(ErrorCode.CATEGORY_HAS_CHILDREN, failure.getCode());
        verify(categoryRepository, never()).save(any(ProductCategory.class));
    }

    @Test
    void selfParentAndGrandchildParentAreRejectedDuringUpdate() {
        UUID categoryId = UUID.randomUUID();
        ProductCategory category = category(categoryId, "Category", null, 0);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

        BusinessException self = assertThrows(BusinessException.class,
                () -> service().updateCategory(categoryId,
                        new CategoryUpsertRequest("Category", null, categoryId)));
        assertEquals(ErrorCode.CATEGORY_PARENT_INVALID, self.getCode());

        UUID secondLevelId = UUID.randomUUID();
        when(categoryRepository.findById(secondLevelId)).thenReturn(
                Optional.of(category(secondLevelId, "Second level", UUID.randomUUID(), 0)));
        BusinessException grandchild = assertThrows(BusinessException.class,
                () -> service().updateCategory(categoryId,
                        new CategoryUpsertRequest("Category", null, secondLevelId)));
        assertEquals(ErrorCode.CATEGORY_PARENT_INVALID, grandchild.getCode());
        verify(categoryRepository, never()).save(any(ProductCategory.class));
    }

    @Test
    void deleteGuardsChildrenAndProductsAndSoftDeletesAnEmptyCategory() {
        UUID parentId = UUID.randomUUID();
        ProductCategory parent = category(parentId, "Parent", null, 0);
        when(categoryRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(categoryRepository.countByParentIdAndIsDeleted(parentId, 0)).thenReturn(1L);
        BusinessException children = assertThrows(BusinessException.class,
                () -> service().deleteCategory(parentId));
        assertEquals(ErrorCode.CATEGORY_HAS_CHILDREN, children.getCode());

        UUID childId = UUID.randomUUID();
        ProductCategory child = category(childId, "Child", parentId, 0);
        when(categoryRepository.findById(childId)).thenReturn(Optional.of(child));
        when(categoryRepository.countByParentIdAndIsDeleted(childId, 0)).thenReturn(0L);
        when(productRepository.countByCategoryIdAndIsDeleted(childId, 0)).thenReturn(1L);
        BusinessException products = assertThrows(BusinessException.class,
                () -> service().deleteCategory(childId));
        assertEquals(ErrorCode.CATEGORY_HAS_PRODUCTS, products.getCode());

        when(productRepository.countByCategoryIdAndIsDeleted(childId, 0)).thenReturn(0L);
        service().deleteCategory(childId);
        ArgumentCaptor<ProductCategory> captor = ArgumentCaptor.forClass(ProductCategory.class);
        verify(categoryRepository).save(captor.capture());
        assertEquals(1, captor.getValue().getIsDeleted());
    }

    @Test
    void updateRejectsDuplicateRootNameAgainstAnotherRoot() {
        UUID categoryId = UUID.randomUUID();
        ProductCategory category = category(categoryId, "Old", null, 0);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
        when(categoryRepository.existsByNameAndParentIdIsNullAndIsDeletedAndIdNot("Taken", 0, categoryId))
                .thenReturn(true);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service().updateCategory(categoryId,
                        new CategoryUpsertRequest(" Taken ", null, null)));

        assertEquals(ErrorCode.CATEGORY_NAME_EXISTS, failure.getCode());
        verify(categoryRepository, never()).save(any(ProductCategory.class));
    }

    private CategoryServiceImpl service() {
        return new CategoryServiceImpl(categoryRepository, productRepository);
    }

    private ProductCategory category(UUID id, String name, UUID parentId, int sortOrder) {
        ProductCategory category = new ProductCategory();
        category.setId(id);
        category.setName(name);
        category.setParentId(parentId);
        category.setSortOrder(sortOrder);
        category.setIsDeleted(0);
        return category;
    }
}
