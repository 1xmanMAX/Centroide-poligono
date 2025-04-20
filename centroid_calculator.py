import numpy as np
import matplotlib.pyplot as plt

class CentroidCalculator:
    def __init__(self):
        self.figures = {
            "triangle": self.triangle_centroid,
            "rectangle": self.rectangle_centroid,
            "circle": self.circle_centroid,
            "polygon": self.polygon_centroid
        }
    
    def triangle_centroid(self, vertices):
        """Calculate the centroid of a triangle given its vertices."""
        vertices = np.array(vertices)
        centroid = np.mean(vertices, axis=0)
        return centroid
    
    def rectangle_centroid(self, vertices):
        """Calculate the centroid of a rectangle given its vertices."""
        vertices = np.array(vertices)
        centroid = np.mean(vertices, axis=0)
        return centroid
    
    def circle_centroid(self, center, radius):
        """Return the center of a circle as its centroid."""
        return np.array(center)
    
    def polygon_centroid(self, vertices):
        """Calculate the centroid of a polygon given its vertices."""
        vertices = np.array(vertices)
        n = len(vertices)
        
        if n < 3:
            raise ValueError("A polygon must have at least 3 vertices")
            
        # Initialize area and centroid components
        area = 0
        centroid_x = 0
        centroid_y = 0
        
        # Using the correct Shoelace formula for area and centroid calculation
        for i in range(n):
            j = (i + 1) % n
            cross_product = vertices[i, 0] * vertices[j, 1] - vertices[j, 0] * vertices[i, 1]
            area += cross_product
            centroid_x += (vertices[i, 0] + vertices[j, 0]) * cross_product
            centroid_y += (vertices[i, 1] + vertices[j, 1]) * cross_product
        
        # Finalize the calculations
        area = abs(area) / 2.0
        
        # Check for zero area to avoid division by zero
        if area < 1e-10:
            raise ValueError("The polygon has zero area. Check if vertices are collinear.")
            
        centroid_x = centroid_x / (6.0 * area)
        centroid_y = centroid_y / (6.0 * area)
        
        return np.array([centroid_x, centroid_y])
    
    def calculate(self, figure_type, *args):
        """Calculate the centroid based on the figure type."""
        if figure_type.lower() in self.figures:
            return self.figures[figure_type.lower()](*args)
        else:
            raise ValueError(f"Figure type '{figure_type}' not supported.")
    
    def plot_figure(self, figure_type, centroid, *args):
        """Plot the figure and its centroid."""
        plt.figure(figsize=(8, 6))
        
        if figure_type.lower() == "triangle" or figure_type.lower() == "rectangle" or figure_type.lower() == "polygon":
            vertices = args[0]
            vertices = np.array(vertices)
            # Close the polygon by connecting the last point to the first
            vertices_closed = np.vstack([vertices, vertices[0]])
            plt.plot(vertices_closed[:, 0], vertices_closed[:, 1], 'b-', label=figure_type.capitalize())
        
        elif figure_type.lower() == "circle":
            center, radius = args
            circle = plt.Circle(center, radius, fill=False, color='b', label='Circle')
            plt.gca().add_patch(circle)
            plt.xlim(center[0] - radius * 1.5, center[0] + radius * 1.5)
            plt.ylim(center[1] - radius * 1.5, center[1] + radius * 1.5)
        
        # Plot the centroid
        plt.plot(centroid[0], centroid[1], 'ro', label='Centroid')
        
        plt.grid(True)
        plt.axis('equal')
        plt.legend()
        plt.title(f'Centroid of {figure_type.capitalize()}')
        plt.xlabel('X')
        plt.ylabel('Y')
        plt.show()

def main():
    calculator = CentroidCalculator()
    
    print("Centroid Calculator")
    print("------------------")
    print("Available figures: triangle, rectangle, circle, polygon")
    
    while True:
        figure_type = input("\nEnter figure type (or 'exit' to quit): ").strip().lower()
        
        if figure_type == 'exit':
            break
        
        if figure_type not in calculator.figures:
            print(f"Figure type '{figure_type}' not supported.")
            continue
        
        try:
            if figure_type == 'triangle':
                print("Enter the coordinates of the three vertices:")
                vertices = []
                for i in range(3):
                    x = float(input(f"Vertex {i+1} x-coordinate: "))
                    y = float(input(f"Vertex {i+1} y-coordinate: "))
                    vertices.append([x, y])
                
                centroid = calculator.calculate(figure_type, vertices)
                print(f"The centroid is at: ({centroid[0]:.2f}, {centroid[1]:.2f})")
                
                plot = input("Do you want to see a plot? (y/n): ").strip().lower()
                if plot == 'y':
                    calculator.plot_figure(figure_type, centroid, vertices)
            
            elif figure_type == 'rectangle':
                print("Enter the coordinates of the four vertices (in order):")
                vertices = []
                for i in range(4):
                    x = float(input(f"Vertex {i+1} x-coordinate: "))
                    y = float(input(f"Vertex {i+1} y-coordinate: "))
                    vertices.append([x, y])
                
                centroid = calculator.calculate(figure_type, vertices)
                print(f"The centroid is at: ({centroid[0]:.2f}, {centroid[1]:.2f})")
                
                plot = input("Do you want to see a plot? (y/n): ").strip().lower()
                if plot == 'y':
                    calculator.plot_figure(figure_type, centroid, vertices)
            
            elif figure_type == 'circle':
                x = float(input("Center x-coordinate: "))
                y = float(input("Center y-coordinate: "))
                radius = float(input("Radius: "))
                
                centroid = calculator.calculate(figure_type, [x, y], radius)
                print(f"The centroid is at: ({centroid[0]:.2f}, {centroid[1]:.2f})")
                
                plot = input("Do you want to see a plot? (y/n): ").strip().lower()
                if plot == 'y':
                    calculator.plot_figure(figure_type, centroid, [x, y], radius)
            
            elif figure_type == 'polygon':
                n = int(input("Enter the number of vertices: "))
                print(f"Enter the coordinates of the {n} vertices (in order):")
                vertices = []
                for i in range(n):
                    x = float(input(f"Vertex {i+1} x-coordinate: "))
                    y = float(input(f"Vertex {i+1} y-coordinate: "))
                    vertices.append([x, y])
                
                centroid = calculator.calculate(figure_type, vertices)
                print(f"The centroid is at: ({centroid[0]:.2f}, {centroid[1]:.2f})")
                
                plot = input("Do you want to see a plot? (y/n): ").strip().lower()
                if plot == 'y':
                    calculator.plot_figure(figure_type, centroid, vertices)
        
        except ValueError as e:
            print(f"Error: {e}")
        except Exception as e:
            print(f"An unexpected error occurred: {e}")

if __name__ == "__main__":
    main()